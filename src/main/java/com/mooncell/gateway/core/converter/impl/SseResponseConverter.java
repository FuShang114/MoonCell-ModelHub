package com.mooncell.gateway.core.converter.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mooncell.gateway.core.converter.ResponseConverter;
import com.mooncell.gateway.core.model.ModelInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 流式响应转换器
 * <p>
 * 专门处理 SSE (Server-Sent Events) 格式的流式响应。
 * 将下游的 SSE 数据块转换为统一的输出格式。
 * 解析 SSE 行后委托给其他 ResponseConverter（如 OpenApiResponseConverter）做 JSON 转换。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseResponseConverter implements ResponseConverter {
    
    private final ObjectMapper objectMapper;
    private final List<ResponseConverter> responseConverters;
    
    /** 排除自身，获取实际做 JSON 转换的转换器，避免循环依赖 */
    private ResponseConverter getContentConverter(ModelInstance instance) {
        return responseConverters.stream()
            .filter(c -> c != this)
            .filter(c -> c.supports(instance))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No response converter found for instance"));
    }
    
    @Override
    public List<String> convert(ModelInstance instance, JsonNode instanceResponse, 
                               String defaultRequestId, AtomicInteger seqCounter) {
        return getContentConverter(instance).convert(instance, instanceResponse, defaultRequestId, seqCounter);
    }
    
    /**
     * 转换 SSE 数据块（包含多行）
     *
     * @param chunk            下游返回的原始片段（可能包含多行 SSE）
     * @param instance         对应的模型实例
     * @param defaultRequestId 默认请求 ID
     * @param seqCounter       序号计数器
     * @return 统一封装后的字符串列表
     */
    public List<String> convertSseChunk(String chunk, ModelInstance instance, 
                                       String defaultRequestId, AtomicInteger seqCounter) {
        List<String> outputs = new ArrayList<>();
        if (chunk == null || chunk.isEmpty()) {
            return outputs;
        }
        
        // Raw 模式：直接返回归一化后的原始行
        if (Boolean.TRUE.equals(instance.getResponseRawEnabled())) {
            String[] lines = chunk.split("\n");
            for (String line : lines) {
                String payload = normalizeSsePayload(line);
                if (payload != null) {
                    outputs.add(payload);
                }
            }
            return outputs;
        }
        
        // 标准模式：转换为统一格式
        String[] lines = chunk.split("\n");
        for (String line : lines) {
            String payload = normalizeSsePayload(line);
            if (payload == null) {
                continue;
            }
            
            // 特殊标记 "[DONE]" 原样透传
            if ("[DONE]".equals(payload)) {
                outputs.add("[DONE]");
                continue;
            }
            
            try {
                JsonNode root = objectMapper.readTree(payload);
                var responseConverter = getContentConverter(instance);
                List<String> converted = responseConverter.convert(
                    instance, root, defaultRequestId, seqCounter);
                outputs.addAll(converted);
            } catch (Exception e) {
                log.debug("Failed to parse SSE data chunk: {}", e.getMessage());
            }
        }
        
        return outputs;
    }
    
    /**
     * 规范化单行 SSE 数据，提取出有效 payload
     * <p>
     * 优化：减少字符串操作，避免多次 trim() 和 substring() 调用。
     */
    private String normalizeSsePayload(String line) {
        if (line == null) {
            return null;
        }
        
        // 先 trim 一次，后续直接操作索引避免重复 trim
        int start = 0;
        int end = line.length();
        
        // 去除前导空白
        while (start < end && Character.isWhitespace(line.charAt(start))) {
            start++;
        }
        
        // 去除尾随空白
        while (end > start && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        
        if (start >= end) {
            return null;
        }
        
        // 去除 "data:" 前缀（可能有多个）
        while (end - start >= 5 && line.regionMatches(true, start, "data:", 0, 5)) {
            start += 5;
            // 去除前缀后的空白
            while (start < end && Character.isWhitespace(line.charAt(start))) {
                start++;
            }
        }
        
        if (start >= end) {
            return null;
        }
        
        // 检查是否为 JSON 对象或 [DONE] 标记
        char firstChar = line.charAt(start);
        char lastChar = line.charAt(end - 1);
        if (firstChar == '{' && lastChar == '}') {
            return line.substring(start, end);
        }
        if (end - start == 6 && line.regionMatches(true, start, "[DONE]", 0, 6)) {
            return "[DONE]";
        }
        
        return null;
    }
    
    @Override
    public boolean supports(ModelInstance instance) {
        // SSE 转换器支持所有实例
        return true;
    }
}
