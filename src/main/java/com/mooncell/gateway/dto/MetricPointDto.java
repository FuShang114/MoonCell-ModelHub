package com.mooncell.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 监控指标数据点DTO
 * 用于时序数据展示
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "监控指标时序数据点")
public class MetricPointDto {

    @Schema(description = "时间戳（毫秒）", example = "1718000000000")
    private Long timestamp;

    @Schema(description = "该时间点对应的指标值", example = "0.85")
    private Double value;
}
