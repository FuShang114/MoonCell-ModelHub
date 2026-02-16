package com.mooncell.gateway.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
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

    public void recordSuccess(long latency) {
        this.circuitOpen = false;
        this.failureCount.set(0);
        this.requestCount.incrementAndGet();
        this.totalLatency.addAndGet(latency);
        this.lastUsedTime = System.currentTimeMillis();
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public void recordFailure() {
        int failures = this.failureCount.incrementAndGet();
        this.lastFailureTime = System.currentTimeMillis();
        if (failures >= 3) {
            this.circuitOpen = true;
        }
    }
    
    public void updateHeartbeat() {
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

}


