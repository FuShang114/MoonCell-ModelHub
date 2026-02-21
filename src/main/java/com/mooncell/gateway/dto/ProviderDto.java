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
}
