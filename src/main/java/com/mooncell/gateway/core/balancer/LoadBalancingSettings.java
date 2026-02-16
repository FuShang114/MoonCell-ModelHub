package com.mooncell.gateway.core.balancer;

public class LoadBalancingSettings {
    private LoadBalancingAlgorithm algorithm;
    private int sampleCount;
    private int objectPoolCoreSize;
    private int objectPoolMaxSize;

    public static LoadBalancingSettings defaultSettings() {
        LoadBalancingSettings settings = new LoadBalancingSettings();
        settings.setAlgorithm(LoadBalancingAlgorithm.TRADITIONAL);
        settings.setSampleCount(2);
        settings.setObjectPoolCoreSize(8);
        settings.setObjectPoolMaxSize(24);
        return settings;
    }

    public LoadBalancingAlgorithm getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(LoadBalancingAlgorithm algorithm) {
        this.algorithm = algorithm;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(int sampleCount) {
        this.sampleCount = Math.max(1, sampleCount);
    }

    public int getObjectPoolCoreSize() {
        return objectPoolCoreSize;
    }

    public void setObjectPoolCoreSize(int objectPoolCoreSize) {
        this.objectPoolCoreSize = Math.max(1, objectPoolCoreSize);
    }

    public int getObjectPoolMaxSize() {
        return objectPoolMaxSize;
    }

    public void setObjectPoolMaxSize(int objectPoolMaxSize) {
        this.objectPoolMaxSize = Math.max(1, objectPoolMaxSize);
    }

    public LoadBalancingSettings copy() {
        LoadBalancingSettings copy = new LoadBalancingSettings();
        copy.setAlgorithm(this.algorithm);
        copy.setSampleCount(this.sampleCount);
        copy.setObjectPoolCoreSize(this.objectPoolCoreSize);
        copy.setObjectPoolMaxSize(this.objectPoolMaxSize);
        return copy;
    }
}
