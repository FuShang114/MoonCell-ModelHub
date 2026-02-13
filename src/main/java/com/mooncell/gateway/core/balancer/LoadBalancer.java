package com.mooncell.gateway.core.balancer;

import com.mooncell.gateway.core.dao.ModelInstanceMapper;
import com.mooncell.gateway.core.model.ModelInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 负载均衡器整合版
 * 维护并发安全的实例集合，整合负载均衡和并发控制功能
 * 调度策略：随机采样 + 最少占用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoadBalancer {
    
    private final ModelInstanceMapper modelMapper;
    
    private static final int SAMPLE_COUNT = 2;
    private final CopyOnWriteArrayList<InstanceWrapper> instanceList = new CopyOnWriteArrayList<>();
    private final Map<String, InstanceWrapper> instanceMap = new ConcurrentHashMap<>();
    
    /**
     * 初始化或刷新实例队列
     */
    public synchronized void refreshInstances() {
        try {
            log.info("Refreshing instance queue...");
            instanceList.clear();
            instanceMap.clear();
            
            // 重新加载所有实例
            List<ModelInstance> allInstances = modelMapper.findAll();
            if (allInstances != null && !allInstances.isEmpty()) {
                for (ModelInstance instance : allInstances) {
                    InstanceWrapper wrapper = new InstanceWrapper(instance);
                    instanceList.add(wrapper);
                    instanceMap.put(instance.getUrl(), wrapper);
                }
                log.info("Loaded {} instances into queue", allInstances.size());
            }
        } catch (Exception e) {
            log.error("Failed to refresh instances", e);
        }
    }

    /**
     * 获取下一个可用实例（整合负载均衡和并发控制 + 负载令牌桶）
     * 随机采样 + 最少占用
     * @return 可用实例，如果无可用实例返回null
     */
    public ModelInstance getNextAvailableInstance() {
        if (instanceList.isEmpty()) {
            refreshInstances();
        }

        int size = instanceList.size();
        if (size == 0) {
            return null;
        }

        int sampleCount = Math.min(SAMPLE_COUNT, size);
        List<InstanceWrapper> samples = new ArrayList<>(sampleCount);
        Set<Integer> seen = new HashSet<>(sampleCount);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (samples.size() < sampleCount && seen.size() < size) {
            int idx = random.nextInt(size);
            if (seen.add(idx)) {
                samples.add(instanceList.get(idx));
            }
        }

        samples.sort(Comparator.comparingInt(InstanceWrapper::getCurrentConcurrency));
        for (InstanceWrapper wrapper : samples) {
            if (wrapper.tryAcquire()) {
                log.debug("Acquired instance: {} (Concurrent: {}/{})",
                        wrapper.getUrl(), wrapper.getCurrentConcurrency(), wrapper.getMaxConcurrency());
                return wrapper.getInstance();
            }
        }

        log.debug("No available instances found");
        return null;
    }

    /**
     * 释放实例（并发计数减1）
     * @param instance 要释放的实例
     */
    public void releaseInstance(ModelInstance instance) {
        if (instance == null) {
            return;
        }

        InstanceWrapper wrapper = instanceMap.get(instance.getUrl());
        if (wrapper != null) {
            wrapper.release();
            log.debug("Released instance: {} (Concurrent: {}/{})", 
                wrapper.getUrl(), wrapper.getCurrentConcurrency(), wrapper.getMaxConcurrency());
        }
    }

    /**
     * 实例包装器
         * 封装实例状态、并发管理与负载令牌桶
     */
    private static class InstanceWrapper {
        private final ModelInstance instance;
        private final AtomicInteger currentConcurrency;
        private final Object tokenLock = new Object();
        private int availableTokens;
        private long lastRefillNanos;
        
        public InstanceWrapper(ModelInstance instance) {
            this.instance = instance;
            this.currentConcurrency = new AtomicInteger(0);
            this.availableTokens = getMaxQps();
            this.lastRefillNanos = System.nanoTime();
        }
        
        /**
         * 原子性增加并发计数
         */
        public boolean tryAcquire() {
            if (!isHealthy()) {
                return false;
            }
            synchronized (tokenLock) {
                refillTokens();
                if (availableTokens <= 0) {
                    return false;
                }
                int current = currentConcurrency.get();
                if (current >= getMaxConcurrency()) {
                    return false;
                }
                availableTokens--;
                currentConcurrency.incrementAndGet();
                return true;
            }
        }

        
        /**
         * 释放实例（并发计数减1，但不能小于0）
         */
        public void release() {
            synchronized (tokenLock) {
                int current = currentConcurrency.get();
                if (current > 0) {
                    currentConcurrency.decrementAndGet();
                }
            }
        }
        
        /**
         * 检查实例是否健康
         */
        public boolean isHealthy() {
            return instance.isHealthy();
        }
        
        /**
         * 获取最大并发
         */
        public int getMaxConcurrency() {
            return instance.getMaxQps() != null ? instance.getMaxQps() : 10;
        }

        public int getMaxQps() {
            return instance.getMaxQps() != null ? instance.getMaxQps() : 10;
        }
        
        /**
         * 获取当前并发
         */
        public int getCurrentConcurrency() {
            return currentConcurrency.get();
        }
        
        /**
         * 获取可用并发
         */
        public int getAvailableConcurrency() {
            return Math.max(0, getMaxConcurrency() - currentConcurrency.get());
        }

        public int getAvailableTokens() {
            synchronized (tokenLock) {
                refillTokens();
                return availableTokens;
            }
        }
        
        /**
         * 获取实例
         */
        public ModelInstance getInstance() {
            return instance;
        }
        
        /**
         * 获取URL
         */
        public String getUrl() {
            return instance.getUrl();
        }

        private void refillTokens() {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            if (elapsed <= 0) {
                return;
            }
            int rate = getMaxQps();
            long tokensToAdd = (elapsed * rate) / 1_000_000_000L;
            if (tokensToAdd > 0) {
                availableTokens = (int) Math.min(rate, availableTokens + tokensToAdd);
                lastRefillNanos = now;
            }
        }
    }

    /**
     * 获取所有实例列表
     */
    public List<ModelInstance> getInstanceList() {
        return instanceList.stream()
            .map(InstanceWrapper::getInstance)
            .collect(Collectors.toList());
    }

    /**
     * 获取队列统计信息
     */
    public QueueStats getStats() {
        int totalInstances = instanceList.size();
        int healthyInstances = (int) instanceList.stream().filter(InstanceWrapper::isHealthy).count();
        int availableConcurrency = instanceList.stream()
            .filter(InstanceWrapper::isHealthy)
            .mapToInt(wrapper -> Math.min(wrapper.getAvailableConcurrency(), wrapper.getAvailableTokens()))
            .sum();
        
        return new QueueStats(totalInstances, healthyInstances, availableConcurrency, System.currentTimeMillis());
    }

    /**
     * 队列统计信息
     */
    public static class QueueStats {
        private final int totalInstances;
        private final int healthyInstances;
        private final int availableQps;
        private final long lastWindowReset;
        
        public QueueStats(int totalInstances, int healthyInstances, int availableQps, long lastWindowReset) {
            this.totalInstances = totalInstances;
            this.healthyInstances = healthyInstances;
            this.availableQps = availableQps;
            this.lastWindowReset = lastWindowReset;
        }
        
        public int getTotalInstances() { return totalInstances; }
        public int getHealthyInstances() { return healthyInstances; }
        public int getAvailableQps() { return availableQps; }
        public long getLastWindowReset() { return lastWindowReset; }
    }
}