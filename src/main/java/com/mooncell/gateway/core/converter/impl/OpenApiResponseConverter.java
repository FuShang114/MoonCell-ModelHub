package com.mooncell.gateway.core.converter.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mooncell.gateway.api.ChatCompletionResponse;
import com.mooncell.gateway.core.converter.ResponseConverter;
import com.mooncell.gateway.core.model.ModelInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 OpenAPI Schema 的响应转换器
 * <p>
 * 支持两种模式：
 * 1. Raw 模式：直接返回原始响应
 * 2. 路径映射模式：使用 JSONPath 提取字段并转换为标准格式
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenApiResponseConverter implements ResponseConverter {
    
    private final ObjectMapper objectMapper;
    
    @Override
    public List<String> convert(ModelInstance instance, JsonNode instanceResponse, 
                               String defaultRequestId, AtomicInteger seqCounter) {
        List<String> outputs = new ArrayList<>();
        
        if (instanceResponse == null) {
            return outputs;
        }
        
        // Raw 模式：直接返回原始响应
        if (Boolean.TRUE.equals(instance.getResponseRawEnabled())) {
            String jsonString = instanceResponse.toString();
            outputs.add(jsonString);
            return outputs;
        }
        
        // 路径映射模式：转换为标准格式
        try {
            ChatCompletionResponse standardResponse = convertToStandardFormat(
                instance, instanceResponse, defaultRequestId, seqCounter);
            
            if (standardResponse != null) {
                String jsonString = objectMapper.writeValueAsString(standardResponse);
                outputs.add(jsonString);
            }
        } catch (Exception e) {
            log.debug("Failed to convert response for instance {}: {}", 
                instance.getModelName(), e.getMessage());
        }
        
        return outputs;
    }
    
    /**
     * 转换为标准格式
     */
    private ChatCompletionResponse convertToStandardFormat(ModelInstance instance, 
                                                          JsonNode instanceResponse,
                                                          String defaultRequestId,
                                                          AtomicInteger seqCounter) {
        // 提取字段
        String requestIdPath = defaultPath(instance.getResponseRequestIdPath(), "id");
        String contentPath = defaultPath(instance.getResponseContentPath(), "choices.0.delta.content");
        String seqPath = defaultPath(instance.getResponseSeqPath(), "choices.0.index");
        
        String requestId = readTextByPath(instanceResponse, requestIdPath);
        if (requestId == null || requestId.isBlank()) {
            requestId = defaultRequestId;
        }
        
        String content = readTextByPath(instanceResponse, contentPath);
        if (content == null) {
            content = "";
        }
        
        Integer seq = readIntByPath(instanceResponse, seqPath);
        if (seq == null) {
            seq = seqCounter.incrementAndGet();
        }
        
        // 构建标准响应
        ChatCompletionResponse.Delta delta = ChatCompletionResponse.Delta.builder()
            .content(content)
            .build();
        
        ChatCompletionResponse.Choice choice = ChatCompletionResponse.Choice.builder()
            .index(seq)
            .delta(delta)
            .build();
        
        return ChatCompletionResponse.builder()
            .id(requestId)
            .object("chat.completion.chunk")
            .model(instance.getModelName())
            .choices(List.of(choice))
            .build();
    }
    
    /**
     * 默认路径
     */
    private String defaultPath(String path, String defaultPath) {
        if (path == null || path.isBlank()) {
            return defaultPath;
        }
        return path;
    }
    
    /**
     * 按路径读取文本
     */
    private String readTextByPath(JsonNode root, String path) {
        JsonNode node = readNodeByPath(root, path);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.asText(null);
    }
    
    /**
     * 按路径读取整数
     */
    private Integer readIntByPath(JsonNode root, String path) {
        JsonNode node = readNodeByPath(root, path);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isInt() || node.isLong()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    /**
     * 简单的点分路径解析器
     */
    private JsonNode readNodeByPath(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        String[] segments = path.split("\\.");
        JsonNode current = root;
        for (String segment : segments) {
            if (current == null) {
                return null;
            }
            if (segment.matches("\\d+")) {
                int index = Integer.parseInt(segment);
                if (!current.isArray() || index >= current.size()) {
                    return null;
                }
                current = current.get(index);
            } else {
                current = current.get(segment);
            }
        }
        return current;
    }
    
    @Override
    public boolean supports(ModelInstance instance) {
        // 默认支持所有实例
        return true;
    }
}
