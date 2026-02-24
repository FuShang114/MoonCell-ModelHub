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
 * 解析 SSE 行后直接委托给 RuleBasedResponseConverter 做 JSON 转换。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseResponseConverter implements ResponseConverter {
    
    private final ObjectMapper objectMapper;
    /**
     * 统一的 JSON 内容转换器：内部再根据实例配置选择规则/默认行为。
     * 这里不再按 supports() 在一堆实现里筛选，避免“谁来处理”与实例配置脱节。
     */
    private final RuleBasedResponseConverter responseConverter;
    
    @Override
    public List<String> convert(ModelInstance instance, JsonNode instanceResponse, 
                               String defaultRequestId, AtomicInteger seqCounter) {
        return responseConverter.convert(instance, instanceResponse, defaultRequestId, seqCounter);
    }
    
    /**
     * 转换 SSE 数据块（包含多行）
     * <p>
     * 优化：使用字符数组操作替代 split()，减少临时对象创建
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
        
        // 优化：手动分割行，避免 split() 创建临时数组
        List<String> lines = splitLines(chunk);
        
        // Raw 模式：直接返回归一化后的原始行
        if (Boolean.TRUE.equals(instance.getResponseRawEnabled())) {
            for (String line : lines) {
                String payload = normalizeSsePayload(line);
                if (payload != null) {
                    outputs.add(payload);
                }
            }
            return outputs;
        }
        
        // 标准模式：转换为统一格式
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
     * 分割字符串为行列表（优化：减少临时对象）
     * <p>
     * 使用 StringBuilder 和字符数组操作，避免 split() 创建临时数组
     */
    private List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        
        int start = 0;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                if (i > start) {
                    lines.add(text.substring(start, i));
                } else {
                    lines.add("");
                }
                start = i + 1;
            }
        }
        
        // 添加最后一行（如果没有以 \n 结尾）
        if (start < len) {
            lines.add(text.substring(start));
        } else if (start == len && len > 0 && text.charAt(len - 1) == '\n') {
            // 如果以 \n 结尾，添加空行
            lines.add("");
        }
        
        return lines;
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
