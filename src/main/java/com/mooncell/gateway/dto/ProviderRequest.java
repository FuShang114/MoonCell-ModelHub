package com.mooncell.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 服务商创建/更新请求DTO
 */
@Data
@Schema(description = "服务商创建/更新请求")
public class ProviderRequest {
    @Schema(description = "服务商名称", example = "openai")
    private String name;

    @Schema(description = "服务商描述", example = "OpenAI 官方服务")
    private String description;
    
    @Schema(description = "请求转换规则（JSON字符串），用于将OpenAPI格式转换为服务商特定格式")
    private String requestConversionRule;
    
    @Schema(description = "响应转换规则（JSON字符串），用于将服务商SSE响应转换为OpenAPI格式")
    private String responseConversionRule;
}
