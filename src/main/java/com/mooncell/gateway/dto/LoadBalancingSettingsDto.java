package com.mooncell.gateway.dto;

public class LoadBalancingSettingsDto {
    private String algorithm;
    private Integer sampleCount;
    private Integer objectPoolCoreSize;
    private Integer objectPoolMaxSize;

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public Integer getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(Integer sampleCount) {
        this.sampleCount = sampleCount;
    }

    public Integer getObjectPoolCoreSize() {
        return objectPoolCoreSize;
    }

    public void setObjectPoolCoreSize(Integer objectPoolCoreSize) {
        this.objectPoolCoreSize = objectPoolCoreSize;
    }

    public Integer getObjectPoolMaxSize() {
        return objectPoolMaxSize;
    }

    public void setObjectPoolMaxSize(Integer objectPoolMaxSize) {
        this.objectPoolMaxSize = objectPoolMaxSize;
    }
}
