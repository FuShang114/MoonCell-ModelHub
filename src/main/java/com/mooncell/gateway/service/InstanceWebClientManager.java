package com.mooncell.gateway.service;

import com.mooncell.gateway.core.model.ModelInstance;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实例专用 WebClient 管理器
 * 
 * <p>为每个模型实例维护专用的 WebClient 和连接池，实现：
 * <ul>
 *   <li><b>按实例复用连接</b>：每个实例有独立的连接池，连接可以更好地复用</li>
 *   <li><b>实例级配置</b>：可以为不同实例配置不同的超时时间</li>
 *   <li><b>自动清理</b>：实例删除时自动清理对应的连接池</li>
 * </ul>
 * 
 * <p>优势：
 * <ul>
 *   <li>连接池更精准：每个实例的连接只用于该实例，避免连接竞争</li>
 *   <li>配置更灵活：可以为不同实例设置不同的超时和连接数</li>
 *   <li>资源更高效：实例删除时立即释放连接池资源</li>
 * </ul>
 */
@Slf4j
@Service
public class InstanceWebClientManager {

    /** 实例 WebClient 缓存，key 为实例 ID */
    private final Map<Long, WebClient> webClientCache = new ConcurrentHashMap<>();
    /** 实例 URL 到 ID 的映射，用于清理时查找 */
    private final Map<String, Long> urlToIdMap = new ConcurrentHashMap<>();
    /** 连接池提供者缓存，key 为实例 ID */
    private final Map<Long, ConnectionProvider> connectionProviderCache = new ConcurrentHashMap<>();
    /** 连接池计数器 */
    private final AtomicInteger poolCounter = new AtomicInteger(0);

    /**
     * 获取实例的专用 WebClient
     * 
     * <p>如果实例的 WebClient 不存在，则创建新的 WebClient 和连接池。
     * 使用实例的 URL 作为连接池的唯一标识。
     * 
     * @param instance 模型实例
     * @return 实例的专用 WebClient
     */
    public WebClient getWebClient(ModelInstance instance) {
        if (instance == null || instance.getId() == null) {
            throw new IllegalArgumentException("Instance and instance ID must not be null");
        }

        Long instanceId = instance.getId();
        String instanceUrl = instance.getUrl();

        // 双重检查锁定模式，确保线程安全
        WebClient webClient = webClientCache.get(instanceId);
        if (webClient != null) {
            return webClient;
        }

        synchronized (this) {
            // 再次检查，避免重复创建
            webClient = webClientCache.get(instanceId);
            if (webClient != null) {
                return webClient;
            }

            // 创建新的连接池和 WebClient
            webClient = createWebClientForInstance(instance);
            webClientCache.put(instanceId, webClient);
            urlToIdMap.put(instanceUrl, instanceId);
            
            log.info("Created WebClient for instance {} (ID: {}, URL: {})", 
                    instance.getName(), instanceId, instanceUrl);
            return webClient;
        }
    }

    /**
     * 为实例创建专用的 WebClient
     * 
     * <p>每个实例使用独立的连接池，配置参数：
     * <ul>
     *   <li>最大连接数：根据实例的 RPM 限制动态计算（最小 10，最大 200）</li>
     *   <li>空闲连接保持时间：20秒</li>
     *   <li>连接最大生命周期：10分钟</li>
     *   <li>超时配置：连接 5秒，读取/写入 60秒</li>
     * </ul>
     * 
     * @param instance 模型实例
     * @return 实例的专用 WebClient
     */
    private WebClient createWebClientForInstance(ModelInstance instance) {
        Long instanceId = instance.getId();
        String instanceUrl = instance.getUrl();
        
        // 根据实例的 RPM 限制动态计算连接池大小
        // 公式：连接数 = max(10, min(200, rpmLimit / 10))
        // 例如：rpmLimit=600 -> 连接数=60，rpmLimit=100 -> 连接数=10
        int rpmLimit = instance.getEffectiveRpmLimit();
        int maxConnections = Math.max(10, Math.min(200, rpmLimit / 10));
        
        // 创建连接池提供者
        String poolName = "instance-pool-" + instanceId + "-" + poolCounter.incrementAndGet();
        ConnectionProvider connectionProvider = ConnectionProvider.builder(poolName)
                .maxConnections(maxConnections)
                .maxIdleTime(Duration.ofSeconds(20))
                .maxLifeTime(Duration.ofMinutes(10))
                .pendingAcquireTimeout(Duration.ofSeconds(5))
                .evictInBackground(Duration.ofSeconds(30))
                .build();
        
        connectionProviderCache.put(instanceId, connectionProvider);

        // 创建 HTTP 客户端
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .responseTimeout(Duration.ofSeconds(60))
                .doOnConnected(conn -> {
                    conn.addHandlerLast(new ReadTimeoutHandler(60, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(60, TimeUnit.SECONDS));
                })
                .compress(true)
                .wiretap(false);

        // 创建 WebClient
        return WebClient.builder()
                .baseUrl(instanceUrl)
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> {
                    configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024); // 10MB
                    configurer.defaultCodecs().enableLoggingRequestDetails(false);
                })
                .build();
    }

    /**
     * 刷新实例列表，清理不再存在的实例的连接池
     * 
     * <p>在 LoadBalancer 刷新实例列表后调用，清理已删除实例的连接池。
     * 
     * @param activeInstanceIds 当前活跃的实例 ID 集合
     */
    public void refreshInstances(Set<Long> activeInstanceIds) {
        // 找出需要清理的实例
        Set<Long> instancesToRemove = ConcurrentHashMap.newKeySet();
        for (Long instanceId : webClientCache.keySet()) {
            if (!activeInstanceIds.contains(instanceId)) {
                instancesToRemove.add(instanceId);
            }
        }

        // 清理不再存在的实例
        for (Long instanceId : instancesToRemove) {
            removeInstance(instanceId);
        }

        if (!instancesToRemove.isEmpty()) {
            log.info("Cleaned up {} instance WebClient(s) during refresh", instancesToRemove.size());
        }
    }

    /**
     * 移除实例的 WebClient 和连接池
     * 
     * @param instanceId 实例 ID
     */
    private void removeInstance(Long instanceId) {
        WebClient webClient = webClientCache.remove(instanceId);
        ConnectionProvider connectionProvider = connectionProviderCache.remove(instanceId);
        
        // 清理 URL 映射
        urlToIdMap.entrySet().removeIf(entry -> entry.getValue().equals(instanceId));
        
        // 释放连接池资源
        if (connectionProvider != null) {
            try {
                connectionProvider.disposeLater().block(Duration.ofSeconds(5));
                log.debug("Disposed connection pool for instance {}", instanceId);
            } catch (Exception e) {
                log.warn("Failed to dispose connection pool for instance {}: {}", instanceId, e.getMessage());
            }
        }
        
        log.info("Removed WebClient for instance {}", instanceId);
    }

    /**
     * 获取当前管理的实例数量
     * 
     * @return 实例数量
     */
    public int getInstanceCount() {
        return webClientCache.size();
    }

    /**
     * 初始化
     */
    @PostConstruct
    public void init() {
        log.info("InstanceWebClientManager initialized");
    }

    /**
     * 清理所有资源
     */
    @PreDestroy
    public void destroy() {
        log.info("Shutting down InstanceWebClientManager, cleaning up {} instances", webClientCache.size());
        
        // 清理所有实例 - 创建快照避免迭代时并发修改
        Set<Long> allInstanceIds = ConcurrentHashMap.newKeySet();
        allInstanceIds.addAll(webClientCache.keySet());
        for (Long instanceId : allInstanceIds) {
            removeInstance(instanceId);
        }
        
        log.info("InstanceWebClientManager shutdown complete");
    }
}
