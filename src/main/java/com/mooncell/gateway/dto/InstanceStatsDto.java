package com.mooncell.gateway.dto;

/**
 * 实例统计数据DTO
 * 用于前端显示实例统计信息
 */
public class InstanceStatsDto {
    private Integer totalInstances;
    private Integer healthyInstances;
    private Integer availableRpm;
    private Integer availableTpm;
    private Long lastWindowReset;
    private String algorithm;

    public InstanceStatsDto() {}

    public InstanceStatsDto(Integer totalInstances, Integer healthyInstances,
                         Integer availableRpm, Integer availableTpm, Long lastWindowReset) {
        this.totalInstances = totalInstances;
        this.healthyInstances = healthyInstances;
        this.availableRpm = availableRpm;
        this.availableTpm = availableTpm;
        this.lastWindowReset = lastWindowReset;
    }

    // Getters and Setters
    public Integer getTotalInstances() { return totalInstances; }
    public void setTotalInstances(Integer totalInstances) { this.totalInstances = totalInstances; }
    
    public Integer getHealthyInstances() { return healthyInstances; }
    public void setHealthyInstances(Integer healthyInstances) { this.healthyInstances = healthyInstances; }
    
    public Integer getAvailableRpm() { return availableRpm; }
    public void setAvailableRpm(Integer availableRpm) { this.availableRpm = availableRpm; }

    public Integer getAvailableTpm() { return availableTpm; }
    public void setAvailableTpm(Integer availableTpm) { this.availableTpm = availableTpm; }

    public Long getLastWindowReset() { return lastWindowReset; }
    public void setLastWindowReset(Long lastWindowReset) { this.lastWindowReset = lastWindowReset; }

    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
}