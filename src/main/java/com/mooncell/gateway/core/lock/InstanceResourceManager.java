package com.mooncell.gateway.core.lock;

import com.mooncell.gateway.core.model.ModelInstance;
import com.mooncell.gateway.core.rate.InstanceRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 实例资源管理器
 * 整合QPS限流和实例锁管理，提供原子性资源获取和释放
 */
@Slf4j
@Component
public class InstanceResourceManager {
    
    private final ConcurrentHashMap<String, InstanceResource> resourceMap = new ConcurrentHashMap<>();
    
    /**
     * 获取实例资源锁
     * @param instance 模型实例
     * @return 资源锁对象，失败返回null
     */
    public InstanceResource acquireResource(ModelInstance instance) {
        String instanceKey = getInstanceKey(instance);
        
        InstanceResource resource = resourceMap.computeIfAbsent(instanceKey, k -> {
            InstanceRateLimiter rateLimiter = new InstanceRateLimiter(instance.getMaxQps());
            return new InstanceResource(instance, rateLimiter, new ReentrantLock());
        });
        
        // 尝试获取资源锁和QPS令牌
        if (resource.tryAcquire()) {
            log.debug("Acquired resource for instance: {} (QPS: {}/{})", 
                instance.getUrl(), 
                resource.getCurrentQpsCount(), 
                resource.getMaxQps());
            return resource;
        }
        
        return null;
    }
    
    /**
     * 释放实例资源锁
     * @param resource 资源锁对象
     */
    public void releaseResource(InstanceResource resource) {
        if (resource != null) {
            resource.release();
            log.debug("Released resource for instance: {} (QPS: {}/{})", 
                resource.getInstance().getUrl(),
                resource.getCurrentQpsCount(),
                resource.getMaxQps());
        }
    }
    
    /**
     * 清理无效的资源
     * @param activeInstances 当前活跃的实例列表
     */
    public void cleanupResources(java.util.List<ModelInstance> activeInstances) {
        activeInstances.forEach(instance -> {
            String key = getInstanceKey(instance);
            if (!resourceMap.containsKey(key)) {
                resourceMap.remove(key);
                log.debug("Cleaned up resource for inactive instance: {}", instance.getUrl());
            }
        });
    }
    
    private String getInstanceKey(ModelInstance instance) {
        return instance.getUrl();
    }
    
    /**
     * 实例资源对象
     * 封装实例、限流器和锁
     */
    public static class InstanceResource {
        private final ModelInstance instance;
        private final InstanceRateLimiter rateLimiter;
        private final ReentrantLock lock;
        private final ThreadLocal<Boolean> acquired = ThreadLocal.withInitial(() -> false);
        
        public InstanceResource(ModelInstance instance, InstanceRateLimiter rateLimiter, ReentrantLock lock) {
            this.instance = instance;
            this.rateLimiter = rateLimiter;
            this.lock = lock;
        }
        
        /**
         * 原子性获取资源
         */
        public boolean tryAcquire() {
            // 先尝试获取QPS令牌
            if (rateLimiter.acquireToken()) {
                // 再获取实例锁
                if (lock.tryLock()) {
                    acquired.set(true);
                    return true;
                } else {
                    // 获取锁失败，释放QPS令牌
                    rateLimiter.releaseToken();
                    return false;
                }
            }
            return false;
        }
        
        /**
         * 释放资源
         */
        public void release() {
            if (acquired.get()) {
                lock.unlock();
                rateLimiter.releaseToken();
                acquired.set(false);
            }
        }
        
        /**
         * 获取模型实例
         */
        public ModelInstance getInstance() {
            return instance;
        }
        
        /**
         * 获取当前QPS计数
         */
        public int getCurrentQpsCount() {
            return rateLimiter.getCurrentStatus().getRequestsInLastSecond();
        }
        
        /**
         * 获取最大QPS
         */
        public int getMaxQps() {
            return rateLimiter.getCurrentStatus().getMaxQps();
        }
        
        /**
         * 获取QPS状态
         */
        public InstanceRateLimiter.QpsStatus getQpsStatus() {
            return rateLimiter.getCurrentStatus();
        }
        
        /**
         * 检查是否已获取资源
         */
        public boolean isAcquired() {
            return acquired.get();
        }
    }
}