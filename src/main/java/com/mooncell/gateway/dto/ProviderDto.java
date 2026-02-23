package com.mooncell.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型服务商信息DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "模型服务商信息")
public class ProviderDto {
    private Long id;
    private String name;
    private String description;
    
    @Schema(description = "请求转换规则（JSON字符串），用于将OpenAPI格式转换为服务商特定格式")
    private String requestConversionRule;
    
    @Schema(description = "响应转换规则（JSON字符串），用于将服务商SSE响应转换为OpenAPI格式")
    private String responseConversionRule;
}
