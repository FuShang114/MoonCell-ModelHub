package com.mooncell.gateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 标准的 OpenAI ChatCompletion 响应格式
 * <p>
 * 作为网关的统一输出格式，转换器会将实例响应转换为此格式。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "标准 ChatCompletion 响应格式（OpenAI 兼容）")
public class ChatCompletionResponse {
    
    @Schema(description = "响应 ID")
    private String id;
    
    @Schema(description = "对象类型", example = "chat.completion.chunk")
    private String object;
    
    @Schema(description = "创建时间戳")
    private Long created;
    
    @Schema(description = "模型名称")
    private String model;
    
    @Schema(description = "选择列表")
    private List<Choice> choices;
    
    @Schema(description = "使用情况")
    private Usage usage;
    
    /**
     * 选择项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "响应选择项")
    public static class Choice {
        @Schema(description = "索引")
        private Integer index;
        
        @Schema(description = "增量内容")
        private Delta delta;
        
        @Schema(description = "完成原因")
        @JsonProperty("finish_reason")
        private String finishReason;
    }
    
    /**
     * 增量内容
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "增量内容")
    public static class Delta {
        @Schema(description = "角色")
        private String role;
        
        @Schema(description = "内容")
        private String content;
    }
    
    /**
     * Token 使用情况
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Token 使用情况")
    public static class Usage {
        @Schema(description = "提示 token 数")
        @JsonProperty("prompt_tokens")
        private Integer promptTokens;
        
        @Schema(description = "完成 token 数")
        @JsonProperty("completion_tokens")
        private Integer completionTokens;
        
        @Schema(description = "总 token 数")
        @JsonProperty("total_tokens")
        private Integer totalTokens;
    }
}
