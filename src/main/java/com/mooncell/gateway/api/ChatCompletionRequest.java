package com.mooncell.gateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 标准的 OpenAI ChatCompletion 请求格式
 * <p>
 * 作为网关的统一输入格式，支持完整的 OpenAI API 规范。
 * 转换器会将此格式转换为各个实例特定的格式。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "标准 ChatCompletion 请求格式（OpenAI 兼容）")
public class ChatCompletionRequest {
    
    @Schema(description = "模型名称", example = "gpt-3.5-turbo", required = true)
    private String model;
    
    @Schema(description = "消息列表", required = true)
    private List<ChatMessage> messages;
    
    @Schema(description = "是否流式输出", example = "true", defaultValue = "false")
    @JsonProperty("stream")
    private Boolean stream;
    
    @Schema(description = "温度参数", example = "0.7")
    private Double temperature;
    
    @Schema(description = "最大 token 数", example = "1000")
    @JsonProperty("max_tokens")
    private Integer maxTokens;
    
    @Schema(description = "Top-p 采样", example = "1.0")
    @JsonProperty("top_p")
    private Double topP;
    
    @Schema(description = "频率惩罚", example = "0.0")
    @JsonProperty("frequency_penalty")
    private Double frequencyPenalty;
    
    @Schema(description = "存在惩罚", example = "0.0")
    @JsonProperty("presence_penalty")
    private Double presencePenalty;
    
    @Schema(description = "停止序列")
    @JsonProperty("stop")
    private List<String> stop;
    
    @Schema(description = "用户标识")
    private String user;
    
    @Schema(description = "幂等键")
    @JsonProperty("idempotency_key")
    private String idempotencyKey;
    
    @Schema(description = "扩展参数")
    @JsonProperty("extra")
    private Map<String, Object> extra;
    
    /**
     * 消息对象
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "聊天消息")
    public static class ChatMessage {
        @Schema(description = "角色", example = "user", required = true)
        private String role;
        
        @Schema(description = "消息内容", example = "你好", required = true)
        private String content;
        
        @Schema(description = "消息名称（可选）")
        private String name;
        
        @Schema(description = "函数调用（可选）")
        @JsonProperty("function_call")
        private Object functionCall;
        
        @Schema(description = "工具调用（可选）")
        @JsonProperty("tool_calls")
        private List<Object> toolCalls;
    }
}
