package com.mooncell.gateway.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对外暴露给前端/上游服务的统一请求 DTO。
 * 仅包含用户纯文本消息和幂等键，有状态信息由网关中台在内部组装。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "AI聊天请求对象")
public class OpenAiRequest {

    @Schema(description = "用户侧纯文本消息", example = "你好，请介绍一下你自己", required = true)
    private String message;

    @Schema(description = "幂等键，用于防止重复请求", example = "uuid-1234-5678", required = false)
    private String idempotencyKey;
}




