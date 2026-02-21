package com.mooncell.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实例统计数据DTO
 * 用于前端显示实例统计信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "实例统计信息")
public class InstanceStatsDto {

    @Schema(description = "实例总数")
    private Integer totalInstances;

    @Schema(description = "健康实例数量")
    private Integer healthyInstances;

    @Schema(description = "当前可用的总RPM额度")
    private Integer availableRpm;

    @Schema(description = "当前可用的总TPM额度")
    private Integer availableTpm;

    @Schema(description = "上一次令牌窗口重置时间戳（毫秒）")
    private Long lastWindowReset;

    @Schema(description = "当前使用的负载均衡算法")
    private String algorithm;

    /**
     * 兼容旧代码使用的五参数构造函数（不含 algorithm 字段）。
     * <p>
     * Lombok 生成的全参构造函数包含 6 个参数（包含 algorithm），
     * 这里显式提供一个只包含统计字段的构造函数，便于业务侧按旧方式创建 DTO，
     * 然后通过 setter 单独设置算法名称。
     */
    public InstanceStatsDto(Integer totalInstances,
                            Integer healthyInstances,
                            Integer availableRpm,
                            Integer availableTpm,
                            Long lastWindowReset) {
        this.totalInstances = totalInstances;
        this.healthyInstances = healthyInstances;
        this.availableRpm = availableRpm;
        this.availableTpm = availableTpm;
        this.lastWindowReset = lastWindowReset;
    }
}