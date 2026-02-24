package com.mooncell.gateway.service;

import com.mooncell.gateway.api.OpenAiRequest;
import com.mooncell.gateway.core.balancer.LoadBalancer;
import com.mooncell.gateway.core.balancer.LoadBalancingAlgorithm;
import com.mooncell.gateway.core.balancer.LoadBalancingSettings;
import com.mooncell.gateway.core.dao.InstanceStore;
import com.mooncell.gateway.core.converter.ConverterFactory;
import com.mooncell.gateway.core.model.ModelInstance;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mooncell.gateway.dto.AddInstanceRequest;
import com.mooncell.gateway.dto.HealthyInstanceDto;
import com.mooncell.gateway.dto.InstanceConfigDto;
import com.mooncell.gateway.dto.InstanceStatsDto;
import com.mooncell.gateway.dto.LoadBalancingSettingsDto;
import com.mooncell.gateway.dto.MonitorMetricsDto;
import com.mooncell.gateway.dto.ProviderDto;
import com.mooncell.gateway.dto.ProviderRequest;
import com.mooncell.gateway.dto.StrategyStatusDto;
import com.mooncell.gateway.service.GatewayService;
import com.mooncell.gateway.service.InstanceWebClientManager;
import com.mooncell.gateway.core.converter.impl.SseResponseConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminService {
    private final InstanceStore instanceStore;
    private final LoadBalancer loadBalancer;
    private final MonitoringMetricsService monitoringMetricsService;
    private final GatewayService gatewayService;
    private final InstanceWebClientManager instanceWebClientManager;
    private final ObjectMapper objectMapper;
    private final HeartbeatService heartbeatService;
    private final ConverterFactory converterFactory;
    private final SseResponseConverter sseResponseConverter;
    // RestartCoordinator 和持久化仅用于手工重启场景；线上接口改为纯热切换后不再通过这里触发进程退出。

    public List<HealthyInstanceDto> getHealthyInstances() {
        log.info("获取健康实例列表");
        List<ModelInstance> allInstances = loadBalancer.getInstanceList();
        return allInstances.stream()
                .filter(ModelInstance::isHealthy)
                .map(this::convertToHealthyDto)
                .collect(Collectors.toList());
    }

    public InstanceStatsDto getInstanceStats() {
        log.info("获取实例统计信息");
        LoadBalancer.QueueStats stats = loadBalancer.getStats();
        InstanceStatsDto dto = new InstanceStatsDto(
                stats.getTotalInstances(),
                stats.getHealthyInstances(),
                stats.getAvailableRpm(),
                stats.getAvailableTpm(),
                stats.getLastWindowReset()
        );
        dto.setAlgorithm(stats.getAlgorithm());
        return dto;
    }

    public String refreshInstances() {
        log.info("刷新实例列表");
        loadBalancer.refreshInstances();
        return "success";
    }

    public String addInstance(AddInstanceRequest request) throws Exception {
        // 1. 获取 providerId
        Long providerId = instanceStore.findProviderIdByName(request.getProvider());
        if (providerId == null) {
            log.error("非法服务商:{}大模型：{}", request.getProvider(), request.getModel());
            throw new Exception("非法服务商");
        }

        // 2. 获取服务商信息，继承转换规则（作为默认值），但允许实例级覆盖
        var providerRecord = instanceStore.findProviderById(providerId);
        String requestConversionRule = request.getRequestConversionRule();
        String responseConversionRule = request.getResponseConversionRule();
        if (requestConversionRule == null || requestConversionRule.isBlank()) {
            requestConversionRule = providerRecord != null ? providerRecord.requestConversionRule() : null;
        }
        if (responseConversionRule == null || responseConversionRule.isBlank()) {
            responseConversionRule = providerRecord != null ? providerRecord.responseConversionRule() : null;
        }
        
        // 3. 插入 DB
        ModelInstance instance = ModelInstance.builder()
                .providerId(providerId)
                .modelName(request.getModel())
                .url(request.getUrl())
                .apiKey(request.getApiKey())
                .postModel(request.getPostModel())
                .responseRequestIdPath(request.getResponseRequestIdPath())
                .responseContentPath(request.getResponseContentPath())
                .responseSeqPath(request.getResponseSeqPath())
                .responseRawEnabled(request.getResponseRawEnabled())
                // 注意：转换规则是“实例级”字段，默认从服务商复制一份，后续可在实例上单独修改
                .requestConversionRule(requestConversionRule)
                .responseConversionRule(responseConversionRule)
                .weight(10)
                .rpmLimit(request.getRpmLimit() != null ? request.getRpmLimit() : 600)
                .tpmLimit(request.getTpmLimit() != null ? request.getTpmLimit() : 600000)
                .maxQps(request.getMaxQps() != null ? request.getMaxQps() : 10)
                .isActive(true)
                .build();

        try {
            instanceStore.insertInstance(instance);
        } catch (IllegalStateException e) {
            log.error("新增实例失败，可能是 URL 唯一约束冲突: {}", request.getUrl(), e);
            throw new Exception("新增失败：URL 已存在（当前配置约束为 URL 唯一）。若需同 URL 多实例，请先调整存储实现。");
        }

        // 4. 同步更新LoadBalancer缓存
        loadBalancer.refreshInstances();

        // 5. 保存后触发一次心跳，尽快探测可用性（异步）
        try {
            heartbeatService.performHeartbeat(instance);
        } catch (Exception e) {
            log.warn("Trigger heartbeat after addInstance failed: {}", e.getMessage());
        }

        return "Instance added and cache refreshed for model: " + request.getModel();
    }

    public List<InstanceConfigDto> getInstances() {
        List<ModelInstance> allInstances = instanceStore.findAllInstances();
        return allInstances.stream()
                .map(instance -> new InstanceConfigDto(
                        instance.getId(),
                        instance.getProviderName(),
                        instance.getModelName(),
                        instance.getUrl(),
                        instance.getApiKey(),
                        instance.getRpmLimit(),
                        instance.getTpmLimit(),
                        instance.getIsActive(),
                        instance.getPostModel(),
                        instance.getResponseRequestIdPath(),
                        instance.getResponseContentPath(),
                        instance.getResponseSeqPath(),
                        instance.getResponseRawEnabled(),
                        instance.getRequestConversionRule(),
                        instance.getResponseConversionRule()
                ))
                .collect(Collectors.toList());
    }

    public String updatePostModel(Long id, String postModel) {
        ModelInstance existing = instanceStore.findById(id);
        if (existing == null) {
            return "not_found";
        }
        existing.setPostModel(postModel);
        boolean ok = instanceStore.updateInstance(existing);
        if (ok) {
            loadBalancer.refreshInstances();
            return "success";
        }
        return "not_found";
    }

    public String updateInstance(Long id, AddInstanceRequest request, Boolean isActive) throws Exception {
        Long providerId = instanceStore.findProviderIdByName(request.getProvider());
        if (providerId == null) {
            log.error("非法服务商:{}大模型：{}", request.getProvider(), request.getModel());
            throw new Exception("非法服务商");
        }
        ModelInstance existing = instanceStore.findById(id);
        if (existing == null) {
            return "not_found";
        }
        
        // 获取服务商信息，继承转换规则
        var providerRecord = instanceStore.findProviderById(providerId);
        // 允许通过请求体传入实例级转换规则；为空时保留原有实例级规则，若原来也为空则回落到服务商默认
        String requestConversionRule = request.getRequestConversionRule();
        String responseConversionRule = request.getResponseConversionRule();
        if (requestConversionRule == null) {
            requestConversionRule = existing.getRequestConversionRule() != null
                    ? existing.getRequestConversionRule()
                    : (providerRecord != null ? providerRecord.requestConversionRule() : null);
        } else if (requestConversionRule.isBlank()) {
            // 前端显式清空时，存 null，表示不使用实例级覆盖
            requestConversionRule = null;
        }
        if (responseConversionRule == null) {
            responseConversionRule = existing.getResponseConversionRule() != null
                    ? existing.getResponseConversionRule()
                    : (providerRecord != null ? providerRecord.responseConversionRule() : null);
        } else if (responseConversionRule.isBlank()) {
            responseConversionRule = null;
        }
        
        ModelInstance updated = ModelInstance.builder()
                .id(id)
                .providerId(providerId)
                .providerName(request.getProvider())
                .modelName(request.getModel())
                .url(request.getUrl())
                .apiKey(request.getApiKey())
                .postModel(request.getPostModel())
                .responseRequestIdPath(request.getResponseRequestIdPath())
                .responseContentPath(request.getResponseContentPath())
                .responseSeqPath(request.getResponseSeqPath())
                .responseRawEnabled(request.getResponseRawEnabled())
                // 转换规则仍然是实例级字段，不会写回到服务商配置
                .requestConversionRule(requestConversionRule)
                .responseConversionRule(responseConversionRule)
                .rpmLimit(request.getRpmLimit() != null ? request.getRpmLimit() : 600)
                .tpmLimit(request.getTpmLimit() != null ? request.getTpmLimit() : 600000)
                .maxQps(request.getMaxQps() != null ? request.getMaxQps() : 10)
                .isActive(isActive != null ? isActive : true)
                .build();
        boolean ok = instanceStore.updateInstance(updated);
        if (ok) {
            loadBalancer.refreshInstances();
            // 保存后触发一次心跳，尽快探测可用性（异步）
            try {
                ModelInstance latest = instanceStore.findById(id);
                if (latest != null) {
                    heartbeatService.performHeartbeat(latest);
                }
            } catch (Exception e) {
                log.warn("Trigger heartbeat after updateInstance failed: {}", e.getMessage());
            }
            return "success";
        }
        return "not_found";
    }

    public List<ProviderDto> getProviders() {
        return instanceStore.findAllProviders();
    }

    public LoadBalancingSettingsDto getLoadBalancingSettings() {
        LoadBalancingSettings settings = loadBalancer.getSettings();
        LoadBalancingSettingsDto dto = new LoadBalancingSettingsDto();
        dto.setAlgorithm(settings.getAlgorithm().name());
        dto.setSampleCount(settings.getSampleCount());
        dto.setSamplingRounds(settings.getSamplingRounds());
        dto.setSamplingSize(settings.getSamplingSize());
        dto.setDynamicBucketingEnabled(settings.isDynamicBucketingEnabled());
        dto.setMaxContextK(settings.getMaxContextK());
        dto.setBucketCount(settings.getBucketCount());
        dto.setBucketRanges(settings.getBucketRanges());
        dto.setBucketWeights(settings.getBucketWeights());
        dto.setHistogramSampleSize(settings.getHistogramSampleSize());
        dto.setBucketUpdateIntervalSeconds(settings.getBucketUpdateIntervalSeconds());
        dto.setBucketUpdateIntervalMinSeconds(settings.getBucketUpdateIntervalMinSeconds());
        dto.setBucketUpdateIntervalMaxSeconds(settings.getBucketUpdateIntervalMaxSeconds());
        dto.setOrderedPoolKeys(settings.getOrderedPoolKeys());
        dto.setQueueCapacity(settings.getQueueCapacity());
        dto.setTTuneIntervalSeconds(settings.getTTuneIntervalSeconds());
        dto.setTCasRetrySampleSize(settings.getTCasRetrySampleSize());
        dto.setTRejectHighThreshold(settings.getTRejectHighThreshold());
        dto.setTForcedReleaseHighThreshold(settings.getTForcedReleaseHighThreshold());
        dto.setTCasRetryP95HighThreshold(settings.getTCasRetryP95HighThreshold());
        dto.setShortBucketWeight(settings.getShortBucketWeight());
        dto.setMediumBucketWeight(settings.getMediumBucketWeight());
        dto.setLongBucketWeight(settings.getLongBucketWeight());
        return dto;
    }

    public LoadBalancingSettingsDto updateLoadBalancingSettings(LoadBalancingSettingsDto request) {
        // 基于当前生效配置构造新配置，并仅做热切换，不再触发应用重启。
        LoadBalancingSettings current = loadBalancer.getSettings();
        LoadBalancingSettings updated = current.copy();
        if (request.getAlgorithm() != null) {
            updated.setAlgorithm(LoadBalancingAlgorithm.fromString(request.getAlgorithm()));
        }
        if (request.getSampleCount() != null) {
            updated.setSampleCount(request.getSampleCount());
        }
        if (request.getSamplingRounds() != null) {
            updated.setSamplingRounds(request.getSamplingRounds());
        }
        if (request.getSamplingSize() != null) {
            updated.setSamplingSize(request.getSamplingSize());
        }
        if (request.getDynamicBucketingEnabled() != null) {
            updated.setDynamicBucketingEnabled(request.getDynamicBucketingEnabled());
        }
        if (request.getMaxContextK() != null) {
            updated.setMaxContextK(request.getMaxContextK());
        }
        if (request.getBucketCount() != null) {
            updated.setBucketCount(request.getBucketCount());
        }
        if (request.getBucketRanges() != null) {
            updated.setBucketRanges(request.getBucketRanges());
        }
        if (request.getBucketWeights() != null) {
            updated.setBucketWeights(request.getBucketWeights());
        }
        if (request.getHistogramSampleSize() != null) {
            updated.setHistogramSampleSize(request.getHistogramSampleSize());
        }
        if (request.getBucketUpdateIntervalSeconds() != null) {
            updated.setBucketUpdateIntervalSeconds(request.getBucketUpdateIntervalSeconds());
        }
        if (request.getBucketUpdateIntervalMinSeconds() != null) {
            updated.setBucketUpdateIntervalMinSeconds(request.getBucketUpdateIntervalMinSeconds());
        }
        if (request.getBucketUpdateIntervalMaxSeconds() != null) {
            updated.setBucketUpdateIntervalMaxSeconds(request.getBucketUpdateIntervalMaxSeconds());
        }
        if (request.getOrderedPoolKeys() != null) {
            updated.setOrderedPoolKeys(request.getOrderedPoolKeys());
        }
        if (request.getQueueCapacity() != null) {
            updated.setQueueCapacity(request.getQueueCapacity());
        }
        if (request.getTTuneIntervalSeconds() != null) {
            updated.setTTuneIntervalSeconds(request.getTTuneIntervalSeconds());
        }
        if (request.getTCasRetrySampleSize() != null) {
            updated.setTCasRetrySampleSize(request.getTCasRetrySampleSize());
        }
        if (request.getTRejectHighThreshold() != null) {
            updated.setTRejectHighThreshold(request.getTRejectHighThreshold());
        }
        if (request.getTForcedReleaseHighThreshold() != null) {
            updated.setTForcedReleaseHighThreshold(request.getTForcedReleaseHighThreshold());
        }
        if (request.getTCasRetryP95HighThreshold() != null) {
            updated.setTCasRetryP95HighThreshold(request.getTCasRetryP95HighThreshold());
        }
        if (request.getShortBucketWeight() != null) {
            updated.setShortBucketWeight(request.getShortBucketWeight());
        }
        if (request.getMediumBucketWeight() != null) {
            updated.setMediumBucketWeight(request.getMediumBucketWeight());
        }
        if (request.getLongBucketWeight() != null) {
            updated.setLongBucketWeight(request.getLongBucketWeight());
        }
        // 使用 LoadBalancer 内部的平滑热切换逻辑，不再触发进程重启。
        loadBalancer.updateSettings(updated);

        // 以热切换后的实际配置为准构造返回 DTO。
        LoadBalancingSettings effective = loadBalancer.getSettings();
        LoadBalancingSettingsDto dto = new LoadBalancingSettingsDto();
        dto.setAlgorithm(effective.getAlgorithm().name());
        dto.setSampleCount(effective.getSampleCount());
        dto.setSamplingRounds(effective.getSamplingRounds());
        dto.setSamplingSize(effective.getSamplingSize());
        dto.setDynamicBucketingEnabled(effective.isDynamicBucketingEnabled());
        dto.setMaxContextK(effective.getMaxContextK());
        dto.setBucketCount(effective.getBucketCount());
        dto.setBucketRanges(effective.getBucketRanges());
        dto.setBucketWeights(effective.getBucketWeights());
        dto.setHistogramSampleSize(effective.getHistogramSampleSize());
        dto.setBucketUpdateIntervalSeconds(effective.getBucketUpdateIntervalSeconds());
        dto.setBucketUpdateIntervalMinSeconds(effective.getBucketUpdateIntervalMinSeconds());
        dto.setBucketUpdateIntervalMaxSeconds(effective.getBucketUpdateIntervalMaxSeconds());
        dto.setOrderedPoolKeys(effective.getOrderedPoolKeys());
        dto.setQueueCapacity(effective.getQueueCapacity());
        dto.setTTuneIntervalSeconds(effective.getTTuneIntervalSeconds());
        dto.setTCasRetrySampleSize(effective.getTCasRetrySampleSize());
        dto.setTRejectHighThreshold(effective.getTRejectHighThreshold());
        dto.setTForcedReleaseHighThreshold(effective.getTForcedReleaseHighThreshold());
        dto.setTCasRetryP95HighThreshold(effective.getTCasRetryP95HighThreshold());
        dto.setShortBucketWeight(effective.getShortBucketWeight());
        dto.setMediumBucketWeight(effective.getMediumBucketWeight());
        dto.setLongBucketWeight(effective.getLongBucketWeight());
        return dto;
    }

    public List<StrategyStatusDto> getStrategyStatuses() {
        return loadBalancer.getStrategyStatuses();
    }

    public MonitorMetricsDto getMonitorMetrics() {
        return monitoringMetricsService.getMonitorMetrics();
    }

    public String resetMetrics() {
        monitoringMetricsService.resetMetrics();
        return "success";
    }

    public String addProvider(ProviderRequest request) {
        instanceStore.insertProvider(request.getName(), request.getDescription(),
                                    request.getRequestConversionRule(), request.getResponseConversionRule());
        return "success";
    }

    public String updateProvider(Long id, ProviderRequest request) {
        boolean updated = instanceStore.updateProvider(id, request.getName(), request.getDescription(),
                                                      request.getRequestConversionRule(), request.getResponseConversionRule());
        return updated ? "success" : "not_found";
    }

    public String deleteProvider(Long id) {
        return instanceStore.deleteProvider(id);
    }

    public String deleteInstance(Long id) {
        log.info("删除实例: {}", id);
        boolean deleted = instanceStore.deleteInstance(id);
        if (deleted) {
            // 刷新负载均衡列表
            loadBalancer.refreshInstances();
            return "success";
        }
        return "not_found";
    }

    public Mono<String> testInstance(Long id, String testMessage) {
        log.info("测试实例: {}, 消息: {}", id, testMessage);
        ModelInstance instance = instanceStore.findById(id);

        if (instance == null) {
            return Mono.just("{\"error\": \"实例不存在\"}");
        }

        final long startTime = System.currentTimeMillis();

        try {
            // 使用与正式请求相同的转换链路，确保测试结果真实可靠
            OpenAiRequest openAiRequest = OpenAiRequest.builder()
                    .message(testMessage != null ? testMessage : "test")
                    .build();

            ObjectNode payload = gatewayService.buildPayloadWithConverter(instance, openAiRequest);

            // 发送请求
            WebClient instanceWebClient = instanceWebClientManager.getWebClient(instance);
            return instanceWebClient.post()
                    .uri(instance.getUrl())
                    .headers(headers -> {
                        headers.setBearerAuth(instance.getApiKey());
                        if ("azure".equalsIgnoreCase(instance.getProviderName())) {
                            headers.set("api-key", instance.getApiKey());
                        }
                        headers.setContentType(MediaType.APPLICATION_JSON);
                    })
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .map(response -> {
                        long elapsed = System.currentTimeMillis() - startTime;
                        List<String> converted = convertTestResponses(instance, response);
                        return buildTestDebugResponse(payload, response, converted, elapsed, null);
                    })
                    .onErrorResume(e -> {
                        log.error("测试实例失败: {}", id, e);
                        long elapsed = System.currentTimeMillis() - startTime;
                        return Mono.just(buildTestDebugResponse(payload, null, null, elapsed, e.getMessage()));
                    });
        } catch (Exception e) {
            log.error("测试实例失败: {}", id, e);
            long elapsed = System.currentTimeMillis() - startTime;
            return Mono.just(buildTestDebugResponse(null, null, null, elapsed, e.getMessage()));
        }
    }

    public Mono<String> testInstanceConfig(com.mooncell.gateway.dto.TestInstanceConfigRequest request) {
        if (request == null || request.getInstance() == null) {
            return Mono.just("{\"error\": \"参数不能为空\"}");
        }
        AddInstanceRequest cfg = request.getInstance();
        String testMessage = request.getTestMessage() != null ? request.getTestMessage() : "ping";

        Long providerId = instanceStore.findProviderIdByName(cfg.getProvider());
        if (providerId == null) {
            return Mono.just("{\"error\": \"非法服务商\"}");
        }
        var providerRecord = instanceStore.findProviderById(providerId);
        // 优先使用当前配置中的实例级转换规则，若为空则回落到服务商默认
        String requestConversionRule = cfg.getRequestConversionRule();
        String responseConversionRule = cfg.getResponseConversionRule();
        if (requestConversionRule == null || requestConversionRule.isBlank()) {
            requestConversionRule = providerRecord != null ? providerRecord.requestConversionRule() : null;
        }
        if (responseConversionRule == null || responseConversionRule.isBlank()) {
            responseConversionRule = providerRecord != null ? providerRecord.responseConversionRule() : null;
        }

        // 构建一个临时实例对象（不落库）
        ModelInstance temp = ModelInstance.builder()
                .id(-1L) // 仅用于构造；不使用 InstanceWebClientManager 缓存
                .providerId(providerId)
                .providerName(cfg.getProvider())
                .modelName(cfg.getModel())
                .url(cfg.getUrl())
                .apiKey(cfg.getApiKey())
                .postModel(cfg.getPostModel())
                .responseRequestIdPath(cfg.getResponseRequestIdPath())
                .responseContentPath(cfg.getResponseContentPath())
                .responseSeqPath(cfg.getResponseSeqPath())
                .responseRawEnabled(cfg.getResponseRawEnabled())
                .requestConversionRule(requestConversionRule)
                .responseConversionRule(responseConversionRule)
                .rpmLimit(cfg.getRpmLimit() != null ? cfg.getRpmLimit() : 600)
                .tpmLimit(cfg.getTpmLimit() != null ? cfg.getTpmLimit() : 600000)
                .maxQps(cfg.getMaxQps() != null ? cfg.getMaxQps() : 10)
                .isActive(true)
                .build();

        final long startTime = System.currentTimeMillis();

        try {
            // 为临时实例绑定请求转换器，使其与正式实例使用相同的转换链路
            temp.ensureRuntimeState();
            temp.setRequestConverter(converterFactory.getRequestConverter(temp));

            // 使用与正式请求相同的转换链路，构造下游请求体
            OpenAiRequest openAiRequest = OpenAiRequest.builder()
                    .message(testMessage)
                    .build();
            ObjectNode payload = gatewayService.buildPayloadWithConverter(temp, openAiRequest);

            // 未保存配置不走 InstanceWebClientManager（其要求 instanceId 非空且用于缓存/连接池）
            HttpClient httpClient = HttpClient.create()
                    .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .responseTimeout(Duration.ofSeconds(60));
            WebClient webClient = WebClient.builder()
                    .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                    .build();

            return webClient.post()
                    .uri(temp.getUrl())
                    .headers(headers -> {
                        headers.setBearerAuth(temp.getApiKey());
                        if ("azure".equalsIgnoreCase(temp.getProviderName())) {
                            headers.set("api-key", temp.getApiKey());
                        }
                        headers.setContentType(MediaType.APPLICATION_JSON);
                    })
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .map(response -> {
                        long elapsed = System.currentTimeMillis() - startTime;
                        List<String> converted = convertTestResponses(temp, response);
                        return buildTestDebugResponse(payload, response, converted, elapsed, null);
                    })
                    .onErrorResume(e -> {
                        log.error("测试实例配置失败", e);
                        long elapsed = System.currentTimeMillis() - startTime;
                        return Mono.just(buildTestDebugResponse(payload, null, null, elapsed, e.getMessage()));
                    });
        } catch (Exception e) {
            log.error("测试实例配置失败", e);
            long elapsed = System.currentTimeMillis() - startTime;
            return Mono.just(buildTestDebugResponse(null, null, null, elapsed, e.getMessage()));
        }
    }

    /**
     * 为“测试连接”接口构造调试信息，包含实际下游请求体和下游原始响应/错误。
     * 这样前端可以直观看到自己的模板（postModel / 转换规则）究竟生成了什么请求。
     */
    private String buildTestDebugResponse(ObjectNode payload,
                                          String downstreamResponse,
                                          List<String> convertedResponses,
                                          Long elapsedMillis,
                                          String errorMessage) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            if (payload != null) {
                // 使用深拷贝避免后续修改影响已返回的数据
                root.set("requestPayload", payload.deepCopy());
            } else {
                root.putNull("requestPayload");
            }
            if (downstreamResponse != null) {
                root.put("downstreamResponse", downstreamResponse);
            } else {
                root.putNull("downstreamResponse");
            }
            if (convertedResponses != null) {
                var arrayNode = objectMapper.createArrayNode();
                for (String s : convertedResponses) {
                    arrayNode.add(s);
                }
                root.set("convertedResponses", arrayNode);
            } else {
                root.putNull("convertedResponses");
            }
            ObjectNode metrics = objectMapper.createObjectNode();
            if (elapsedMillis != null) {
                metrics.put("elapsedMillis", elapsedMillis);
            }
            if (downstreamResponse != null) {
                metrics.put("downstreamLength", downstreamResponse.length());
            }
            if (convertedResponses != null) {
                metrics.put("convertedCount", convertedResponses.size());
            }
            root.set("metrics", metrics);
            if (errorMessage != null) {
                root.put("error", errorMessage);
            } else {
                root.putNull("error");
            }
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            // 兜底：若调试信息构造失败，则至少返回错误原因
            String msg = errorMessage != null ? errorMessage : e.getMessage();
            return "{\"error\": \"" + msg + "\"}";
        }
    }

    /**
     * 在测试链路中复用正式链路的 SSE + 规则转换逻辑，生成 OpenAPI 统一格式的响应片段。
     */
    private List<String> convertTestResponses(ModelInstance instance, String downstreamResponse) {
        if (downstreamResponse == null || downstreamResponse.isBlank()) {
            return null;
        }
        try {
            AtomicInteger seqCounter = new AtomicInteger(0);
            String defaultRequestId = "test-" + (instance.getId() != null ? instance.getId() : "config");
            return sseResponseConverter.convertSseChunk(downstreamResponse, instance, defaultRequestId, seqCounter);
        } catch (Exception e) {
            log.warn("convertTestResponses failed for instance {}: {}", instance.getModelName(), e.getMessage());
            return null;
        }
    }

    public String getRequestPreview(Long id) {
        log.info("获取实例请求格式预览: {}", id);
        ModelInstance instance = instanceStore.findById(id);
        
        if (instance == null) {
            return "{\"error\": \"实例不存在\"}";
        }
        
        try {
            // 构建示例请求体
            ObjectNode payload = gatewayService.buildPayload(instance, "示例消息");
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            log.error("获取请求格式预览失败: {}", id, e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private HealthyInstanceDto convertToHealthyDto(ModelInstance instance) {
        return new HealthyInstanceDto(
                instance.getId(),
                instance.getName(),
                instance.getProviderName(),
                instance.getUrl(),
                instance.getModelName(),
                instance.getRpmLimit(),
                instance.getTpmLimit(),
                instance.getRequestCount() != null ? instance.getRequestCount().get() : 0,
                instance.isHealthy(),
                instance.getLastHeartbeat() > 0 ?
                        new java.util.Date(instance.getLastHeartbeat()).toString() : "从未心跳",
                instance.getFailureCount() != null ? instance.getFailureCount().get() : 0
        );
    }
}
