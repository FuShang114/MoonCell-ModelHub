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
    private final WebClient.Builder webClientBuilder;
    private final GatewayService gatewayService;

    public void checkHealth() {
        List<ModelInstance> allInstances = modelMapper.findAll();
        allInstances.forEach(instance -> {
            if (!instance.isHealthy() || (System.currentTimeMillis() - instance.getLastUsedTime() > 60000)) {
                performHeartbeat(instance);
            }
        });
    }

    public void performHeartbeat(ModelInstance instance) {
        String targetUrl = gatewayService.buildTargetUrl(instance);

        webClientBuilder.build().post()
                .uri(targetUrl)
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
