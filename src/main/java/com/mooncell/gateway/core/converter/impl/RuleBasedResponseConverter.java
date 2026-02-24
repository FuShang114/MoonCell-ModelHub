package com.mooncell.gateway.core.converter.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.mooncell.gateway.api.ChatCompletionResponse;
import com.mooncell.gateway.core.converter.ConversionRule;
import com.mooncell.gateway.core.converter.ResponseConverter;
import com.mooncell.gateway.core.converter.util.PathCache;
import com.mooncell.gateway.core.model.ModelInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于转换规则的响应转换器。
 * <p>
 * 语义约定：
 * <ul>
 *   <li>如果实例未配置响应转换规则（rule 为空），视为下游已是 OpenAPI 格式，直接透传 JSON 文本；</li>
 *   <li>如果配置了规则但解析/应用失败，视为配置错误：抛出异常，由上层统一记失败统计；</li>
 *   <li>不再回退到任何“默认转换器”，避免掩盖配置问题。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleBasedResponseConverter implements ResponseConverter {
    
    private final ObjectMapper objectMapper;
    
    @Override
    public List<String> convert(ModelInstance instance, JsonNode instanceResponse, 
                               String defaultRequestId, AtomicInteger seqCounter) {
        String ruleJson = instance.getResponseConversionRule();
        if (ruleJson == null || ruleJson.isBlank()) {
            // 没有配置转换规则：认为下游已经是 OpenAPI 格式，直接透传 JSON 文本
            List<String> outputs = new ArrayList<>();
            if (instanceResponse != null) {
                outputs.add(instanceResponse.toString());
            }
            return outputs;
        }
        
        try {
            // 直接解析规则字符串
            ConversionRule rule = parseRule(ruleJson);
            
            if (rule.getResponseRule() == null) {
                throw new IllegalStateException("Response conversion rule is missing 'response' definition");
            }
            
            return applyResponseRule(instance, instanceResponse, defaultRequestId, seqCounter, rule.getResponseRule());
        } catch (Exception e) {
            // 规则本身或应用过程出现异常：视为配置错误，直接抛出让上层计入失败统计
            log.warn("Failed to apply response conversion rule for instance {}: {}", 
                instance.getModelName(), e.getMessage());
            throw new IllegalStateException("Failed to apply response conversion rule for instance "
                    + instance.getModelName(), e);
        }
    }
    
    /**
     * 解析转换规则
     */
    private ConversionRule parseRule(String ruleJson) throws Exception {
        JsonNode root = objectMapper.readTree(ruleJson);
        ConversionRule rule = new ConversionRule();
        if (root.isObject()) {
            rule.setRequestRule(root.has("request") ? root.get("request") : null);
            rule.setResponseRule(root.has("response") ? root.get("response") : root);
        } else {
            // 如果整个 JSON 就是响应规则
            rule.setResponseRule(root);
        }
        return rule;
    }
    
    /**
     * 应用响应转换规则
     */
    private List<String> applyResponseRule(ModelInstance instance, JsonNode instanceResponse, 
                                         String defaultRequestId, AtomicInteger seqCounter, JsonNode rule) {
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
        
        String type = rule.has("type") ? rule.get("type").asText() : "template";
        
        ChatCompletionResponse standardResponse;
        if ("template".equals(type)) {
            // 模板模式：使用 JSON 模板
            standardResponse = applyTemplateRule(instance, instanceResponse, defaultRequestId, seqCounter, rule);
        } else if ("mapping".equals(type)) {
            // 映射模式：字段映射
            standardResponse = applyMappingRule(instance, instanceResponse, defaultRequestId, seqCounter, rule);
        } else {
            // 默认使用映射模式（兼容旧的路径映射方式）
            standardResponse = applyMappingRule(instance, instanceResponse, defaultRequestId, seqCounter, rule);
        }
        
        if (standardResponse == null) {
            throw new IllegalStateException("Response conversion using rule returned null for instance "
                    + instance.getModelName());
        }
        
        try {
            String jsonString = objectMapper.writeValueAsString(standardResponse);
            outputs.add(jsonString);
        } catch (Exception e) {
            log.debug("Failed to serialize ChatCompletionResponse for instance {}: {}", 
                instance.getModelName(), e.getMessage());
            throw new IllegalStateException("Failed to serialize ChatCompletionResponse for instance "
                    + instance.getModelName(), e);
        }
        
        return outputs;
    }
    
    /**
     * 应用模板规则
     */
    private ChatCompletionResponse applyTemplateRule(ModelInstance instance, JsonNode instanceResponse,
                                                    String defaultRequestId, AtomicInteger seqCounter, JsonNode rule) {
        JsonNode templateNode = rule.has("template") ? rule.get("template") : rule;
        if (!templateNode.isObject()) {
            // 如果模板无效，返回 null，让上层使用 fallback
            return null;
        }
        
        ObjectNode result = templateNode.deepCopy();
        replacePlaceholders(result, instance, instanceResponse, defaultRequestId, seqCounter, rule);
        
        try {
            return objectMapper.treeToValue(result, ChatCompletionResponse.class);
        } catch (Exception e) {
            log.debug("Failed to convert template result to ChatCompletionResponse: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 应用映射规则
     */
    private ChatCompletionResponse applyMappingRule(ModelInstance instance, JsonNode instanceResponse,
                                                   String defaultRequestId, AtomicInteger seqCounter, JsonNode rule) {
        // 兼容旧的路径映射方式
        String requestIdPath = rule.has("requestIdPath") 
            ? rule.get("requestIdPath").asText() 
            : (instance.getResponseRequestIdPath() != null ? instance.getResponseRequestIdPath() : "id");
        String contentPath = rule.has("contentPath")
            ? rule.get("contentPath").asText()
            : (instance.getResponseContentPath() != null ? instance.getResponseContentPath() : "choices.0.delta.content");
        String seqPath = rule.has("seqPath")
            ? rule.get("seqPath").asText()
            : (instance.getResponseSeqPath() != null ? instance.getResponseSeqPath() : "choices.0.index");
        
        // 提取字段
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
        
        // 应用值转换
        if (rule.has("transformations")) {
            JsonNode transformations = rule.get("transformations");
            if (transformations.has("content")) {
                String transformType = transformations.get("content").asText();
                content = applyContentTransformation(content, transformType);
            }
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
     * 替换占位符
     */
    private void replacePlaceholders(JsonNode node, ModelInstance instance, JsonNode instanceResponse,
                                    String defaultRequestId, AtomicInteger seqCounter, JsonNode rule) {
        if (node == null) return;
        
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            obj.fields().forEachRemaining(entry -> {
                JsonNode child = entry.getValue();
                if (child.isTextual()) {
                    String text = child.asText();
                    JsonNode replacement = resolvePlaceholder(text, instance, instanceResponse, defaultRequestId, seqCounter);
                    if (replacement != null) {
                        obj.set(entry.getKey(), replacement);
                    }
                } else {
                    replacePlaceholders(child, instance, instanceResponse, defaultRequestId, seqCounter, rule);
                }
            });
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                JsonNode child = array.get(i);
                if (child.isTextual()) {
                    String text = child.asText();
                    JsonNode replacement = resolvePlaceholder(text, instance, instanceResponse, defaultRequestId, seqCounter);
                    if (replacement != null) {
                        array.set(i, replacement);
                    }
                } else {
                    replacePlaceholders(child, instance, instanceResponse, defaultRequestId, seqCounter, rule);
                }
            }
        }
    }
    
    /**
     * 解析占位符
     */
    private JsonNode resolvePlaceholder(String text, ModelInstance instance, JsonNode instanceResponse,
                                       String defaultRequestId, AtomicInteger seqCounter) {
        if (text == null || !text.startsWith("$")) return null;
        
        switch (text) {
            case "$requestId":
            case "$id":
                String requestId = readTextByPath(instanceResponse, "id");
                if (requestId == null || requestId.isBlank()) {
                    requestId = defaultRequestId;
                }
                return TextNode.valueOf(requestId);
            case "$content":
                String content = readTextByPath(instanceResponse, "choices.0.delta.content");
                if (content == null) {
                    content = "";
                }
                return TextNode.valueOf(content);
            case "$seq":
            case "$index":
                Integer seq = readIntByPath(instanceResponse, "choices.0.index");
                if (seq == null) {
                    seq = seqCounter.incrementAndGet();
                }
                return objectMapper.getNodeFactory().numberNode(seq);
            case "$model":
                return TextNode.valueOf(instance.getModelName());
            case "$object":
                return TextNode.valueOf("chat.completion.chunk");
            default:
                // 支持 $field 或 $path.field 格式，从 instanceResponse 中读取
                if (text.length() > 1) {
                    String path = text.substring(1);
                    JsonNode value = readNodeByPath(instanceResponse, path);
                    if (value != null) {
                        return value.deepCopy();
                    }
                }
                return null;
        }
    }
    
    /**
     * 应用内容转换
     */
    private String applyContentTransformation(String content, String transformType) {
        if (content == null) {
            return "";
        }
        
        switch (transformType) {
            case "trim":
                return content.trim();
            case "lowercase":
                return content.toLowerCase();
            case "uppercase":
                return content.toUpperCase();
            default:
                return content;
        }
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
     * 按路径读取节点（优化：使用路径缓存）
     */
    private JsonNode readNodeByPath(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        String[] segments = PathCache.splitPath(path);
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
        // 如果实例配置了响应转换规则，则支持
        String ruleJson = instance.getResponseConversionRule();
        return ruleJson != null && !ruleJson.isBlank();
    }
}
