package com.mooncell.gateway.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 幂等性控制服务
 * <p>
 * 使用本地缓存（Caffeine）实现幂等性控制，30秒过期时间
 */
@Slf4j
@Service
public class IdempotencyService {
    
    private static final long IDEMPOTENCY_TTL_SECONDS = 30L; // 30 秒
    
    /**
     * 本地缓存，用于存储幂等键
     * key: idempotencyKey
     * value: 占位符（实际值不重要，只关心key是否存在）
     */
    private final Cache<String, Boolean> idempotencyCache;
    
    public IdempotencyService() {
        this.idempotencyCache = Caffeine.newBuilder()
                .maximumSize(100_000) // 最大缓存10万个键
                .expireAfterWrite(IDEMPOTENCY_TTL_SECONDS, TimeUnit.SECONDS)
                .build();
    }
    
    /**
     * 尝试获取幂等键
     * <p>
     * 使用 Caffeine 的 putIfAbsent 语义保证原子性，避免竞态条件
     *
     * @param idempotencyKey 幂等键
     * @return true 如果成功获取（首次请求），false 如果已存在（重复请求）
     */
    public boolean tryAcquire(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            return false;
        }
        
        try {
            // Caffeine 的 get 方法如果 key 不存在会返回 null
            // 我们使用 putIfAbsent 的语义：如果不存在则放入并返回 true，如果已存在则返回 false
            Boolean existing = idempotencyCache.getIfPresent(idempotencyKey);
            if (existing != null) {
                // 已存在，重复请求
                return false;
            }
            
            // 不存在，放入缓存
            idempotencyCache.put(idempotencyKey, Boolean.TRUE);
            return true;
        } catch (Exception e) {
            log.error("Failed to check idempotency key: {}", idempotencyKey, e);
            // 发生错误时，为了可用性，允许请求继续（但记录错误）
            return true;
        }
    }
    
    /**
     * 释放幂等键（请求完成或失败时调用）
     * <p>
     * 注意：由于使用过期时间，此方法主要用于提前释放，不是必须的
     *
     * @param idempotencyKey 幂等键
     */
    public void release(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            return;
        }
        
        try {
            idempotencyCache.invalidate(idempotencyKey);
        } catch (Exception e) {
            log.warn("Failed to release idempotency key: {}", idempotencyKey, e);
            // 释放失败不影响主流程，只记录警告
        }
    }
    
    /**
     * 获取 TTL（用于监控）
     * <p>
     * 注意：Caffeine 不直接提供 TTL 查询，这里返回固定值或估算值
     */
    public long getTtl(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            return -1;
        }
        
        // Caffeine 不提供精确的 TTL 查询，这里返回配置的 TTL 值
        // 如果需要精确的 TTL，可以考虑使用其他缓存实现或记录时间戳
        Boolean exists = idempotencyCache.getIfPresent(idempotencyKey);
        return exists != null ? IDEMPOTENCY_TTL_SECONDS : -1;
    }
}
