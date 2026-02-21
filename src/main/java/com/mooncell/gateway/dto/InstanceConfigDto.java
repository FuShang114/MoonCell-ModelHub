package com.mooncell.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实例配置DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "AI模型服务实例配置")
public class InstanceConfigDto {
    private Long id;
    private String providerName;
    private String modelName;
    private String url;
    private String apiKey;
    private Integer rpmLimit;
    private Integer tpmLimit;
    private Boolean isActive;
    private String postModel;
    private String responseRequestIdPath;
    private String responseContentPath;
    private String responseSeqPath;
    private Boolean responseRawEnabled;
}
