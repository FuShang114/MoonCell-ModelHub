package com.mooncell.gateway.core.balancer;

import com.mooncell.gateway.core.model.ModelInstance;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class ResourceLockManager {

    // URL -> Semaphore
    private final Map<String, Semaphore> locks = new ConcurrentHashMap<>();
    
    // 默认每个节点最大并发 50 (实际可根据 weight 动态调整)
    private static final int DEFAULT_PERMITS = 100;

    /**
     * 尝试锁定资源
     * @param instance 目标实例
     * @return true 如果成功锁定
     */
    public boolean tryLock(ModelInstance instance) {
        Semaphore semaphore = locks.computeIfAbsent(instance.getUrl(), k -> new Semaphore(DEFAULT_PERMITS));
        try {
            // 尝试获取许可，不需要等待太久，立刻返回结果
            return semaphore.tryAcquire(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 释放资源
     */
    public void release(ModelInstance instance) {
        if (instance == null) {
            return;
        }
        Semaphore semaphore = locks.get(instance.getUrl());
        if (semaphore != null) {
            semaphore.release();
        }
    }
}
