package com.mooncell.gateway.core.balancer;

import com.mooncell.gateway.core.dao.ModelInstanceMapper;
import com.mooncell.gateway.core.model.ModelInstance;
import com.mooncell.gateway.dto.StrategyStatusDto;
import com.mooncell.gateway.service.InstanceWebClientManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoadBalancerStatusContractTest {

    @Test
    void shouldExposeExtendedStrategyStatusFields() {
        ModelInstanceMapper mapper = mock(ModelInstanceMapper.class);
        LoadBalancingSettingsStore store = mock(LoadBalancingSettingsStore.class);
        InstanceWebClientManager webClientManager = mock(InstanceWebClientManager.class);
        when(store.load()).thenReturn(Optional.empty());
        when(mapper.findAll()).thenReturn(List.of(
                ModelInstance.builder()
                        .id(1L)
                        .providerName("p")
                        .modelName("m")
                        .url("http://local")
                        .apiKey("k")
                        .rpmLimit(600)
                        .tpmLimit(600000)
                        .isActive(true)
                        .build()
        ));

        LoadBalancer loadBalancer = new LoadBalancer(mapper, store, webClientManager);
        loadBalancer.init();

        List<StrategyStatusDto> statuses = loadBalancer.getStrategyStatuses();
        assertFalse(statuses.isEmpty());

        StrategyStatusDto s = statuses.get(0);
        assertNotNull(s.getRuntimeId());
        assertNotNull(s.getAlgorithm());
        assertNotNull(s.getState());
        assertNotNull(s.getQueueDepth());
        assertNotNull(s.getQueueCapacity());
        assertNotNull(s.getRejectBudget());
        assertNotNull(s.getRejectSampling());
    }
}
