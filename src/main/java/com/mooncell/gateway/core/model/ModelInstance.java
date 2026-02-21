package com.mooncell.gateway.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 对应数据库中的 model_instance 表
 * 每一个对象代表一个可用的大模型服务节点 (URL级别)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelInstance {
    public static final int FIXED_HOLD_TIMEOUT_SECONDS = 60;
    private Long id;
    private Long providerId;
    private String providerName; // 冗余字段，方便使用
    
    private String modelName; // e.g., "gpt-4"
    private String url;       // 核心唯一标识, e.g. "https://api.openai.com/v1"
    private String apiKey;    // 对应的 Key
    /**
     * 请求体模板（JSON 字符串），由前端配置。
     * 允许使用占位符：$model / $messages
     */
    private String postModel;
    // SSE 响应字段映射路径（点路径）
    private String responseRequestIdPath;
    private String responseContentPath;
    private String responseSeqPath;
    private Boolean responseRawEnabled;
    
    private Integer weight;   // 权重
    private Integer rpmLimit; // 每分钟请求上限
    private Integer tpmLimit; // 每分钟Token上限
    /** 资源池键，用于路由器按池回退；空或 null 时归入 default 池 */
    private String poolKey;
    // 兼容历史字段，逐步废弃
    private Integer maxQps;
    private Boolean isActive; // 数据库中的配置状态
    
    // --- 运行时状态 (不持久化到 DB，或者异步持久化) ---
    
    @Builder.Default
    private transient AtomicInteger failureCount = new AtomicInteger(0);
    @Builder.Default
    private transient AtomicInteger requestCount = new AtomicInteger(0);
    @Builder.Default
    private transient AtomicLong totalLatency = new AtomicLong(0);
    @Builder.Default
    private transient long lastUsedTime = System.currentTimeMillis();
    @Builder.Default
    private transient long lastFailureTime = 0;
    
    // 熔断标志 (内存态，与 isActive 结合使用)
    @Builder.Default
    private transient volatile boolean circuitOpen = false;
    
    // 心跳状态
    @Builder.Default
    private transient long lastHeartbeat = 0;

    // 对象池运行态：0 空闲，1 占用（仅对象池使用，Traditional 忽略）
    @Builder.Default
    private transient AtomicInteger occupiedState = new AtomicInteger(0);
    // 占用超时释放阈值（秒），前端可见只读
    @Builder.Default
    private transient int holdTimeoutSeconds = FIXED_HOLD_TIMEOUT_SECONDS;
    @Builder.Default
    private transient long occupiedAtMs = 0L;
    @Builder.Default
    private transient long occupiedExpireAtMs = 0L;
    @Builder.Default
    private transient Long parentInstanceId = null;

    public void ensureRuntimeState() {
        if (failureCount == null) {
            failureCount = new AtomicInteger(0);
        }
        if (requestCount == null) {
            requestCount = new AtomicInteger(0);
        }
        if (totalLatency == null) {
            totalLatency = new AtomicLong(0);
        }
        if (occupiedState == null) {
            occupiedState = new AtomicInteger(0);
        }
    }

    public void recordSuccess(long latency) {
        ensureRuntimeState();
        this.circuitOpen = false;
        this.failureCount.set(0);
        this.requestCount.incrementAndGet();
        this.totalLatency.addAndGet(latency);
        this.lastUsedTime = System.currentTimeMillis();
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public void recordFailure() {
        ensureRuntimeState();
        int failures = this.failureCount.incrementAndGet();
        this.lastFailureTime = System.currentTimeMillis();
        if (failures >= 3) {
            this.circuitOpen = true;
        }
    }
    
    public void updateHeartbeat() {
        ensureRuntimeState();
        this.lastHeartbeat = System.currentTimeMillis();
    }
    
    // 判断节点是否真实可用 (DB配置开启 + 熔断器未开启)
    public boolean isHealthy() {
        return Boolean.TRUE.equals(isActive) && !circuitOpen;
    }
    
    public String getName() {
        return providerName + "-" + modelName;
    }

    public int getEffectiveRpmLimit() {
        if (rpmLimit != null && rpmLimit > 0) {
            return rpmLimit;
        }
        if (maxQps != null && maxQps > 0) {
            return maxQps * 60;
        }
        return 600;
    }

    public int getEffectiveTpmLimit() {
        if (tpmLimit != null && tpmLimit > 0) {
            return tpmLimit;
        }
        return 600000;
    }

    public boolean tryOccupy(long nowMs, int timeoutSeconds) {
        ensureRuntimeState();
        if (occupiedState.compareAndSet(0, 1)) {
            // T 写死 60 秒：忽略调用方传入 timeoutSeconds，保证全局一致
            this.holdTimeoutSeconds = FIXED_HOLD_TIMEOUT_SECONDS;
            this.occupiedAtMs = nowMs;
            this.occupiedExpireAtMs = nowMs + (long) this.holdTimeoutSeconds * 1000L;
            return true;
        }
        return false;
    }

    public void forceRelease() {
        ensureRuntimeState();
        occupiedState.set(0);
        occupiedAtMs = 0L;
        occupiedExpireAtMs = 0L;
    }

    public boolean isOccupied() {
        ensureRuntimeState();
        return occupiedState.get() == 1;
    }

    public boolean isExpired(long nowMs) {
        return isOccupied() && occupiedExpireAtMs > 0 && nowMs >= occupiedExpireAtMs;
    }

    public ModelInstance deepCopyForPool(long objectId, int timeoutSeconds) {
        ModelInstance copy = ModelInstance.builder()
                .id(objectId)
                .providerId(this.providerId)
                .providerName(this.providerName)
                .modelName(this.modelName)
                .url(this.url)
                .apiKey(this.apiKey)
                .postModel(this.postModel)
                .responseRequestIdPath(this.responseRequestIdPath)
                .responseContentPath(this.responseContentPath)
                .responseSeqPath(this.responseSeqPath)
                .responseRawEnabled(this.responseRawEnabled)
                .weight(this.weight)
                .rpmLimit(this.rpmLimit)
                .tpmLimit(this.tpmLimit)
                .maxQps(this.maxQps)
                .isActive(this.isActive)
                .build();
        copy.parentInstanceId = Objects.requireNonNullElse(this.id, objectId);
        // T 写死 60 秒：忽略调用方传入 timeoutSeconds，保证全局一致
        copy.holdTimeoutSeconds = FIXED_HOLD_TIMEOUT_SECONDS;
        copy.forceRelease();
        return copy;
    }

}


