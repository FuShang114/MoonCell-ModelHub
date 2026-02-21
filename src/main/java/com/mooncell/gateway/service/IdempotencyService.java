package com.mooncell.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 幂等性控制服务
 * <p>
 * 优化点：
 * <ul>
 *   <li>使用 Lua 脚本保证原子性操作</li>
 *   <li>减少 Redis 往返次数</li>
 *   <li>支持批量操作（未来扩展）</li>
 * </ul>
 */
@Slf4j
@Service
public class IdempotencyService {
    
    private static final String IDEMPOTENCY_KEY_PREFIX = "mooncell:idempotency:";
    private static final long IDEMPOTENCY_TTL_SECONDS = 300L; // 5 分钟
    
    /**
     * Lua 脚本：原子性检查并设置
     * <p>
     * 如果 key 不存在，则设置并返回 1（成功）
     * 如果 key 已存在，则返回 0（重复请求）
     */
    private static final String CHECK_AND_SET_SCRIPT = 
        "if redis.call('exists', KEYS[1]) == 0 then " +
        "  redis.call('setex', KEYS[1], ARGV[1], '1') " +
        "  return 1 " +
        "else " +
        "  return 0 " +
        "end";
    
    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> checkAndSetScript;
    
    public IdempotencyService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        // 初始化 Lua 脚本
        this.checkAndSetScript = new DefaultRedisScript<>();
        this.checkAndSetScript.setScriptText(CHECK_AND_SET_SCRIPT);
        this.checkAndSetScript.setResultType(Long.class);
    }
    
    /**
     * 尝试获取幂等键
     * <p>
     * 使用 Lua 脚本保证原子性，避免竞态条件
     *
     * @param idempotencyKey 幂等键
     * @return true 如果成功获取（首次请求），false 如果已存在（重复请求）
     */
    public boolean tryAcquire(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            return false;
        }
        
        String redisKey = IDEMPOTENCY_KEY_PREFIX + idempotencyKey;
        
        try {
            // 使用 Lua 脚本原子性操作
            Long result = stringRedisTemplate.execute(
                checkAndSetScript,
                Collections.singletonList(redisKey),
                String.valueOf(IDEMPOTENCY_TTL_SECONDS)
            );
            
            return result != null && result == 1L;
        } catch (Exception e) {
            log.error("Failed to check idempotency key: {}", idempotencyKey, e);
            // 发生错误时，为了可用性，允许请求继续（但记录错误）
            // 生产环境可以考虑更严格的策略
            return true;
        }
    }
    
    /**
     * 释放幂等键（请求完成或失败时调用）
     *
     * @param idempotencyKey 幂等键
     */
    public void release(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            return;
        }
        
        String redisKey = IDEMPOTENCY_KEY_PREFIX + idempotencyKey;
        
        try {
            stringRedisTemplate.delete(redisKey);
        } catch (Exception e) {
            log.warn("Failed to release idempotency key: {}", idempotencyKey, e);
            // 释放失败不影响主流程，只记录警告
        }
    }
    
    /**
     * 获取完整的 Redis key
     */
    public static String getRedisKey(String idempotencyKey) {
        return IDEMPOTENCY_KEY_PREFIX + idempotencyKey;
    }
    
    /**
     * 获取 TTL（用于监控）
     */
    public long getTtl(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            return -1;
        }
        
        String redisKey = IDEMPOTENCY_KEY_PREFIX + idempotencyKey;
        Long ttl = stringRedisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
        return ttl != null ? ttl : -1;
    }
}
