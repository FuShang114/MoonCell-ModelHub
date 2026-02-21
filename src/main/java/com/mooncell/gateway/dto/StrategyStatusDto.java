package com.mooncell.gateway.dto;

import lombok.Data;

/**
 * 策略运行状态DTO
 * 用于展示负载均衡策略的运行时状态信息
 */
@Data
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
    private Integer objectHoldSeconds;
    private Integer queueDepth;
    private Integer queueCapacity;
    private Integer bucketCount;
    private String bucketRanges;
    private String bucketWeights;
    private Integer formulaRpm;
    private Integer formulaTpm;
    private Integer formulaTotal;
    private Integer totalObjects;
    private String bucketObjectCounts;
    private String bucketOccupiedCounts;
    private String bucketUsageRates;
    private Integer lastResizeDeleted;
    private Integer lastResizeAdded;
    private Integer activeOccupiedObjects;
    private Long drainDurationMs;
    private Long rejectQueueFull;
    private Long rejectBudget;
    private Long rejectSampling;
    private Double tAllocSuccessRate;
    private Double tRejectRate;
    private Double tForcedReleaseRate;
    private Double tCasRetryPerSuccess;
    private Double tCasRetryP95;
}
