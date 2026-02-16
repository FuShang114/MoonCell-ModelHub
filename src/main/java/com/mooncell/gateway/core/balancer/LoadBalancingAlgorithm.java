package com.mooncell.gateway.core.balancer;

public enum LoadBalancingAlgorithm {
    TRADITIONAL,
    OBJECT_POOL;

    public static LoadBalancingAlgorithm fromString(String value) {
        if (value == null || value.isBlank()) {
            return TRADITIONAL;
        }
        for (LoadBalancingAlgorithm algorithm : values()) {
            if (algorithm.name().equalsIgnoreCase(value)) {
                return algorithm;
            }
        }
        return TRADITIONAL;
    }
}
