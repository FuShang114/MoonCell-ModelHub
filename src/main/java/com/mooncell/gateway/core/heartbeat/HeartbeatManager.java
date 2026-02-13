package com.mooncell.gateway.core.heartbeat;

import com.mooncell.gateway.service.HeartbeatService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HeartbeatManager {

    private final HeartbeatService heartbeatService;

    // 每 30 秒执行一次心跳检测
    @Scheduled(fixedRate = 30000)
    public void checkHealth() {
        heartbeatService.checkHealth();
    }
}
