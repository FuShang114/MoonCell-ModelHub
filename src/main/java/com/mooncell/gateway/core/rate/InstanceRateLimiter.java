package com.mooncell.gateway.core.rate;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实例级别QPS限流器
 * 使用滑动时间窗口算法实现精确的QPS控制
 */
@Slf4j
public class InstanceRateLimiter {
    
    private static final long WINDOW_SIZE_MS = 1000L; // 1秒窗口
    private static final int WINDOW_SLICE_MS = 100;   // 100ms精度
    
    private final int maxQps;
    private final AtomicInteger currentRequests = new AtomicInteger(0);
    private final AtomicLong windowStartTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger lastSliceCount = new AtomicInteger(0);
    private final AtomicInteger currentSliceCount = new AtomicInteger(0);
    
    public InstanceRateLimiter(int maxQps) {
        this.maxQps = maxQps;
    }
    
    /**
     * 原子性获取令牌
     * @return true表示获取成功，false表示超出QPS限制
     */
    public boolean acquireToken() {
        long now = System.currentTimeMillis();
        long windowStart = windowStartTime.get();
        
        // 检查是否需要重置窗口
        if (now - windowStart >= WINDOW_SIZE_MS) {
            synchronized (this) {
                long currentWindowStart = windowStartTime.get();
                if (now - currentWindowStart >= WINDOW_SIZE_MS) {
                    // 重置时间窗口
                    windowStartTime.set(now);
                    
                    // 滑动窗口计算：移除最旧时间片，添加新时间片
                    int totalInLastSecond = lastSliceCount.get() + currentSliceCount.get();
                    
                    // 重置计数
                    lastSliceCount.set(currentSliceCount.get());
                    currentSliceCount.set(0);
                    
                    log.debug("Window reset: total requests in last second = {}", totalInLastSecond);
                    
                    // 如果上一个窗口内请求数超限，则跳过当前时间片
                    if (totalInLastSecond >= maxQps) {
                        return false;
                    }
                }
            }
        }
        
        // 原子性检查和增加当前时间片计数
        int currentCount = currentSliceCount.get();
        int maxAllowed = Math.max(1, maxQps / (int)(WINDOW_SIZE_MS / WINDOW_SLICE_MS)); // 每100ms允许的最大请求数
        
        if (currentCount < maxAllowed) {
            return currentSliceCount.compareAndSet(currentCount, currentCount + 1);
        }
        
        return false;
    }
    
    /**
     * 原子性释放令牌
     */
    public void releaseToken() {
        int current = currentSliceCount.get();
        if (current > 0) {
            currentSliceCount.compareAndSet(current, current - 1);
        }
    }
    
    /**
     * 获取当前QPS状态
     */
    public QpsStatus getCurrentStatus() {
        return new QpsStatus(
            maxQps,
            currentRequests.get(),
            lastSliceCount.get() + currentSliceCount.get(),
            windowStartTime.get()
        );
    }
    
    /**
     * 重置限流器状态
     */
    public void reset() {
        synchronized (this) {
            windowStartTime.set(System.currentTimeMillis());
            currentRequests.set(0);
            lastSliceCount.set(0);
            currentSliceCount.set(0);
        }
    }
    
    /**
     * QPS状态信息
     */
    @Data
    public static class QpsStatus {
        private final int maxQps;
        private final int currentRequests;
        private final int requestsInLastSecond;
        private final long windowStartTime;

        public double getUsageRate() {
            return (double) requestsInLastSecond / maxQps;
        }
    }
}