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
import com.mooncell.gateway.dto.ProviderDto;
import com.mooncell.gateway.dto.ProviderRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminService {
    private final ModelInstanceMapper mapper;
    private final LoadBalancer loadBalancer;

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

        mapper.insert(instance);

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
        dto.setObjectPoolCoreSize(settings.getObjectPoolCoreSize());
        dto.setObjectPoolMaxSize(settings.getObjectPoolMaxSize());
        return dto;
    }

    public LoadBalancingSettingsDto updateLoadBalancingSettings(LoadBalancingSettingsDto request) {
        LoadBalancingSettings current = loadBalancer.getSettings();
        LoadBalancingSettings updated = current.copy();
        if (request.getAlgorithm() != null) {
            updated.setAlgorithm(LoadBalancingAlgorithm.fromString(request.getAlgorithm()));
        }
        if (request.getSampleCount() != null) {
            updated.setSampleCount(request.getSampleCount());
        }
        if (request.getObjectPoolCoreSize() != null) {
            updated.setObjectPoolCoreSize(request.getObjectPoolCoreSize());
        }
        if (request.getObjectPoolMaxSize() != null) {
            updated.setObjectPoolMaxSize(request.getObjectPoolMaxSize());
        }
        loadBalancer.updateSettings(updated);
        return getLoadBalancingSettings();
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
