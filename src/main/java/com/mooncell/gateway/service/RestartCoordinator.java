package com.mooncell.gateway.service;

import com.mooncell.gateway.core.balancer.LoadBalancer;
import com.mooncell.gateway.core.balancer.LoadBalancingSettings;
import com.mooncell.gateway.core.balancer.LoadBalancingSettingsStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RestartCoordinator {
    private final LoadBalancer loadBalancer;
    private final LoadBalancingSettingsStore settingsStore;
    private final InflightRequestTracker inflightRequestTracker;
    private final ConfigurableApplicationContext applicationContext;

    public void requestRestartWithSettings(LoadBalancingSettings newSettings) {
        // 新版本中，重启逻辑由外部进程管理器负责，这里只负责持久化配置并给出提示日志。
        boolean saved = settingsStore.save(newSettings);
        if (!saved) {
            log.warn("Restart requested but failed to persist settings; skip restart to avoid losing config.");
            return;
        }
        loadBalancer.stopAcceptingNewRequests();
        log.info("Restart requested with settings persisted. Please restart the application manually.");
    }
}

