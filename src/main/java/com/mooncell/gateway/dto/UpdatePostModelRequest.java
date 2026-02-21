package com.mooncell.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 更新实例请求体模板DTO
 */
@Data
@Schema(description = "更新实例 post_model 请求")
public class UpdatePostModelRequest {
    @Schema(description = "请求体模板(JSON字符串)", example = "{\"stream\":true,\"messages\":\"$messages\"}")
    private String postModel;
}
