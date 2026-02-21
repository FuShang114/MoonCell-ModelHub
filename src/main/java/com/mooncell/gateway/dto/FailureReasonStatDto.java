package com.mooncell.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 失败原因统计DTO
 * 用于统计和展示请求失败的原因分布
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "请求失败原因统计信息")
public class FailureReasonStatDto {

    @Schema(description = "失败原因内部编码", example = "DOWNSTREAM_ERROR")
    private String reasonKey;

    @Schema(description = "失败原因可读描述", example = "下游模型接口错误")
    private String reason;

    @Schema(description = "该原因下累计失败次数", example = "123")
    private Long count;

    @Schema(description = "该原因在所有失败中的占比", example = "0.35")
    private Double ratio;
}

