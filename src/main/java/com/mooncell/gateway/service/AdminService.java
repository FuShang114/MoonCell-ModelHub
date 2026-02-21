package com.mooncell.gateway.service;

import com.mooncell.gateway.core.balancer.LoadBalancer;
import com.mooncell.gateway.core.balancer.LoadBalancingAlgorithm;
import com.mooncell.gateway.core.balancer.LoadBalancingSettings;
import com.mooncell.gateway.core.dao.ModelInstanceMapper;
import com.mooncell.gateway.core.model.ModelInstance;
import com.mooncell.gateway.dto.AddInstanceRequest;
import com.mooncell.gateway.dto.HealthyInstanceDto;
import com.mooncell.gateway.dto.InstanceConfigDto;
import com.mooncell.gateway.dto.InstanceStatsDto;
import com.mooncell.gateway.dto.LoadBalancingSettingsDto;
import com.mooncell.gateway.dto.MonitorMetricsDto;
import com.mooncell.gateway.dto.ProviderDto;
import com.mooncell.gateway.dto.ProviderRequest;
import com.mooncell.gateway.dto.StrategyStatusDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminService {
    private final ModelInstanceMapper mapper;
    private final LoadBalancer loadBalancer;
    private final MonitoringMetricsService monitoringMetricsService;
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
        Long providerId = mapper.findProviderIdByName(request.getProvider());
        if (providerId == null) {
            log.error("非法服务商:{}大模型：{}", request.getProvider(), request.getModel());
            throw new Exception("非法服务商");
        }

        // 2. 插入 DB
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
                .weight(10)
                .rpmLimit(request.getRpmLimit() != null ? request.getRpmLimit() : 600)
                .tpmLimit(request.getTpmLimit() != null ? request.getTpmLimit() : 600000)
                .maxQps(request.getMaxQps() != null ? request.getMaxQps() : 10)
                .isActive(true)
                .build();

        try {
            mapper.insert(instance);
        } catch (DataIntegrityViolationException e) {
            log.error("新增实例失败，可能是 URL 唯一约束冲突: {}", request.getUrl(), e);
            throw new Exception("新增失败：URL 已存在（当前库约束为 URL 唯一）。若需同 URL 多实例，请先调整数据库唯一键。");
        }

        // 3. 同步更新LoadBalancer缓存
        loadBalancer.refreshInstances();

        return "Instance added and cache refreshed for model: " + request.getModel();
    }

    public List<InstanceConfigDto> getInstances() {
        List<ModelInstance> allInstances = mapper.findAll();
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
                        instance.getResponseRawEnabled()
                ))
                .collect(Collectors.toList());
    }

    public String updatePostModel(Long id, String postModel) {
        int updated = mapper.updatePostModel(id, postModel);
        if (updated > 0) {
            loadBalancer.refreshInstances();
            return "success";
        }
        return "not_found";
    }

    public String updateInstance(Long id, AddInstanceRequest request, Boolean isActive) throws Exception {
        Long providerId = mapper.findProviderIdByName(request.getProvider());
        if (providerId == null) {
            log.error("非法服务商:{}大模型：{}", request.getProvider(), request.getModel());
            throw new Exception("非法服务商");
        }
        int updated = mapper.updateInstance(
                id,
                providerId,
                request.getModel(),
                request.getUrl(),
                request.getApiKey(),
                request.getPostModel(),
                request.getResponseRequestIdPath(),
                request.getResponseContentPath(),
                request.getResponseSeqPath(),
                request.getResponseRawEnabled(),
                request.getRpmLimit() != null ? request.getRpmLimit() : 600,
                request.getTpmLimit() != null ? request.getTpmLimit() : 600000,
                request.getMaxQps() != null ? request.getMaxQps() : 10,
                isActive != null ? isActive : true
        );
        if (updated > 0) {
            loadBalancer.refreshInstances();
            return "success";
        }
        return "not_found";
    }

    public List<ProviderDto> getProviders() {
        return mapper.findAllProviders();
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
        mapper.insertProvider(request.getName(), request.getDescription());
        return "success";
    }

    public String updateProvider(Long id, ProviderRequest request) {
        int updated = mapper.updateProvider(id, request.getName(), request.getDescription());
        return updated > 0 ? "success" : "not_found";
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
