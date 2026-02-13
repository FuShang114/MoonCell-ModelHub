package com.mooncell.gateway.dto;

/**
 * 实例统计数据DTO
 * 用于前端显示实例统计信息
 */
public class InstanceStatsDto {
    private Integer totalInstances;
    private Integer healthyInstances;
    // 语义为可用负载（并发任务数）
    private Integer availableQps;
    private Long lastWindowReset;

    public InstanceStatsDto() {}

    public InstanceStatsDto(Integer totalInstances, Integer healthyInstances, 
                         Integer availableQps, Long lastWindowReset) {
        this.totalInstances = totalInstances;
        this.healthyInstances = healthyInstances;
        this.availableQps = availableQps;
        this.lastWindowReset = lastWindowReset;
    }

    // Getters and Setters
    public Integer getTotalInstances() { return totalInstances; }
    public void setTotalInstances(Integer totalInstances) { this.totalInstances = totalInstances; }
    
    public Integer getHealthyInstances() { return healthyInstances; }
    public void setHealthyInstances(Integer healthyInstances) { this.healthyInstances = healthyInstances; }
    
    public Integer getAvailableQps() { return availableQps; }
    public void setAvailableQps(Integer availableQps) { this.availableQps = availableQps; }
    
    public Long getLastWindowReset() { return lastWindowReset; }
    public void setLastWindowReset(Long lastWindowReset) { this.lastWindowReset = lastWindowReset; }
}