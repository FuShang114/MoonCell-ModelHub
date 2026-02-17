package com.mooncell.gateway.dto;

public class StrategyStatusDto {
    private String runtimeId;
    private String algorithm;
    private String state;
    private Integer inflightLeases;
    private Long sinceEpochMs;
    private Integer shortBoundaryTokens;
    private Integer mediumBoundaryTokens;
    private Integer shortWeight;
    private Integer mediumWeight;
    private Integer longWeight;

    public String getRuntimeId() {
        return runtimeId;
    }

    public void setRuntimeId(String runtimeId) {
        this.runtimeId = runtimeId;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Integer getInflightLeases() {
        return inflightLeases;
    }

    public void setInflightLeases(Integer inflightLeases) {
        this.inflightLeases = inflightLeases;
    }

    public Long getSinceEpochMs() {
        return sinceEpochMs;
    }

    public void setSinceEpochMs(Long sinceEpochMs) {
        this.sinceEpochMs = sinceEpochMs;
    }

    public Integer getShortBoundaryTokens() {
        return shortBoundaryTokens;
    }

    public void setShortBoundaryTokens(Integer shortBoundaryTokens) {
        this.shortBoundaryTokens = shortBoundaryTokens;
    }

    public Integer getMediumBoundaryTokens() {
        return mediumBoundaryTokens;
    }

    public void setMediumBoundaryTokens(Integer mediumBoundaryTokens) {
        this.mediumBoundaryTokens = mediumBoundaryTokens;
    }

    public Integer getShortWeight() {
        return shortWeight;
    }

    public void setShortWeight(Integer shortWeight) {
        this.shortWeight = shortWeight;
    }

    public Integer getMediumWeight() {
        return mediumWeight;
    }

    public void setMediumWeight(Integer mediumWeight) {
        this.mediumWeight = mediumWeight;
    }

    public Integer getLongWeight() {
        return longWeight;
    }

    public void setLongWeight(Integer longWeight) {
        this.longWeight = longWeight;
    }
}
