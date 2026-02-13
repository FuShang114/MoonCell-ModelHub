package com.mooncell.gateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 更新实例请求体模板
 */
@Schema(description = "更新实例 post_model 请求")
public class UpdatePostModelRequest {
    @Schema(description = "请求体模板(JSON字符串)", example = "{\"stream\":true,\"messages\":\"$messages\"}")
    private String postModel;

    public String getPostModel() { return postModel; }
    public void setPostModel(String postModel) { this.postModel = postModel; }
}
