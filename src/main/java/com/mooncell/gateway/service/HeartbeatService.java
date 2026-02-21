package com.mooncell.gateway.service;

import com.mooncell.gateway.core.dao.ModelInstanceMapper;
import com.mooncell.gateway.core.model.ModelInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class HeartbeatService {
    private final ModelInstanceMapper modelMapper;
    private final InstanceWebClientManager instanceWebClientManager;  // 使用实例专用 WebClient 管理器
    private final GatewayService gatewayService;

    /**
     * 周期性巡检所有模型实例健康状态
     * <p>
     * 对于已标记不健康或超过一定时间未使用的实例，会主动发起一次心跳请求，
     * 以便尽快探测恢复并更新实例状态。
     */
    public void checkHealth() {
        List<ModelInstance> allInstances = modelMapper.findAll();
        allInstances.forEach(instance -> {
            if (!instance.isHealthy() || (System.currentTimeMillis() - instance.getLastUsedTime() > 60000)) {
                performHeartbeat(instance);
            }
        });
    }

    /**
     * 对单个实例执行一次心跳检测
     * <p>
     * 使用与业务请求相同的下游 URL 和 payload 构造规则，只是内容固定为 "ping"，
     * 成功（2xx）则认为实例恢复并调用 {@link ModelInstance#recordSuccess(int)}。
     *
     * @param instance 需要探测的模型实例
     */
    public void performHeartbeat(ModelInstance instance) {
        // 使用实例专用的 WebClient，复用连接池
        WebClient instanceWebClient = instanceWebClientManager.getWebClient(instance);
        instanceWebClient.post()
                .uri(instance.getUrl())
                .headers(headers -> {
                    headers.add("Content-Type", "application/json");
                    headers.setBearerAuth(instance.getApiKey());
                    if ("azure".equalsIgnoreCase(instance.getProviderName())) {
                        headers.set("api-key", instance.getApiKey());
                    }
                })
                .bodyValue(gatewayService.buildPayload(instance, "ping"))
                .retrieve()
                .toBodilessEntity()
                .subscribe(
                        response -> {
                            if (response.getStatusCode().is2xxSuccessful()) {
                                log.info("Instance {} recovered.", instance.getUrl());
                                instance.recordSuccess(0);
                            } else {
                                log.warn("Instance {} heartbeat failed: {}", instance.getUrl(), response.getStatusCode());
                            }
                        },
                        error -> log.error("Instance {} heartbeat error: {}", instance.getUrl(), error.getMessage())
                );
    }
}
