package com.mooncell.gateway.dto;

/**
 * 健康实例DTO
 * 用于前端显示健康节点信息
 */
public class HealthyInstanceDto {
    private Long id;
    private String name;
    private String providerName;
    private String url;
    private String modelName;
    private Integer maxQps;
    private Integer requestCount;
    private Boolean healthy;
    private String lastHeartbeat;
    private Integer failureCount;

    public HealthyInstanceDto() {}

    public HealthyInstanceDto(Long id, String name, String providerName, String url, 
                           String modelName, Integer maxQps, Integer requestCount, 
                           Boolean healthy, String lastHeartbeat, Integer failureCount) {
        this.id = id;
        this.name = name;
        this.providerName = providerName;
        this.url = url;
        this.modelName = modelName;
        this.maxQps = maxQps;
        this.requestCount = requestCount;
        this.healthy = healthy;
        this.lastHeartbeat = lastHeartbeat;
        this.failureCount = failureCount;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }
    
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    
    public Integer getMaxQps() { return maxQps; }
    public void setMaxQps(Integer maxQps) { this.maxQps = maxQps; }
    
    public Integer getRequestCount() { return requestCount; }
    public void setRequestCount(Integer requestCount) { this.requestCount = requestCount; }
    
    public Boolean getHealthy() { return healthy; }
    public void setHealthy(Boolean healthy) { this.healthy = healthy; }
    
    public String getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(String lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
    
    public Integer getFailureCount() { return failureCount; }
    public void setFailureCount(Integer failureCount) { this.failureCount = failureCount; }
}