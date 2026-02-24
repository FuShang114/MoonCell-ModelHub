package com.mooncell.gateway.core.converter.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.mooncell.gateway.core.converter.ConversionRule;
import com.mooncell.gateway.core.converter.RequestConverter;
import com.mooncell.gateway.core.converter.util.PathCache;
import com.mooncell.gateway.core.model.ModelInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 基于转换规则的请求转换器
 * <p>
 * 所有请求都统一走规则引擎：
 * - 如果未配置请求规则或规则缺失 request 段，则视为配置错误，抛出异常；
 * - 不再回退到 OpenApiRequestConverter。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleBasedRequestConverter implements RequestConverter {
    
    private final ObjectMapper objectMapper;
    
    @Override
    public JsonNode convert(ModelInstance instance, JsonNode gatewayRequest) {
        String ruleJson = instance.getRequestConversionRule();
        if (ruleJson == null || ruleJson.isBlank()) {
            throw new IllegalStateException("Request conversion rule is not configured for instance: "
                    + instance.getModelName());
        }

        try {
            // 直接解析规则字符串
            ConversionRule rule = parseRule(ruleJson);

            if (rule.getRequestRule() == null) {
                throw new IllegalStateException("Request rule segment is missing for instance: "
                        + instance.getModelName());
            }

            return applyRequestRule(instance, gatewayRequest, rule.getRequestRule());
        } catch (Exception e) {
            // 任何规则解析/应用失败都直接向上抛，让调用方计入失败统计
            log.warn("Failed to apply request conversion rule for instance {}: {}",
                    instance.getModelName(), e.getMessage());
            throw new IllegalStateException("Failed to apply request conversion rule for instance "
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
            rule.setRequestRule(root.has("request") ? root.get("request") : root);
            rule.setResponseRule(root.has("response") ? root.get("response") : null);
        } else {
            // 如果整个 JSON 就是请求规则
            rule.setRequestRule(root);
        }
        return rule;
    }
    
    /**
     * 应用请求转换规则
     */
    private JsonNode applyRequestRule(ModelInstance instance, JsonNode gatewayRequest, JsonNode rule) {
        if (rule == null) {
            throw new IllegalStateException("Request rule is null for instance: " + instance.getModelName());
        }

        String type = rule.has("type") ? rule.get("type").asText() : "template";
        
        if ("template".equals(type)) {
            // 模板模式：使用 JSON 模板
            return applyTemplateRule(instance, gatewayRequest, rule);
        } else if ("mapping".equals(type)) {
            // 映射模式：字段映射
            return applyMappingRule(instance, gatewayRequest, rule);
        } else {
            // 默认使用模板模式
            return applyTemplateRule(instance, gatewayRequest, rule);
        }
    }
    
    /**
     * 应用模板规则
     */
    private JsonNode applyTemplateRule(ModelInstance instance, JsonNode gatewayRequest, JsonNode rule) {
        JsonNode templateNode = rule.has("template") ? rule.get("template") : rule;
        if (!templateNode.isObject()) {
            throw new IllegalStateException("Template request rule must be an object for instance: "
                    + instance.getModelName());
        }

        ObjectNode result = templateNode.deepCopy();
        replacePlaceholders(result, instance, gatewayRequest, rule);
        return result;
    }
    
    /**
     * 应用映射规则
     */
    private JsonNode applyMappingRule(ModelInstance instance, JsonNode gatewayRequest, JsonNode rule) {
        ObjectNode result = objectMapper.createObjectNode();
        JsonNode mapping = rule.has("mapping") ? rule.get("mapping") : rule;
        
        if (mapping.isObject()) {
            mapping.fields().forEachRemaining(entry -> {
                String targetKey = entry.getKey();
                String sourcePath = entry.getValue().asText();
                JsonNode sourceValue = readNodeByPath(gatewayRequest, sourcePath);
                if (sourceValue != null) {
                    result.set(targetKey, sourceValue.deepCopy());
                }
            });
        }
        
        // 应用值转换
        if (rule.has("transformations")) {
            applyTransformations(result, rule.get("transformations"));
        }
        
        return result;
    }
    
    /**
     * 替换占位符
     */
    private void replacePlaceholders(JsonNode node, ModelInstance instance, 
                                    JsonNode gatewayRequest, JsonNode rule) {
        if (node == null) return;
        
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            obj.fields().forEachRemaining(entry -> {
                JsonNode child = entry.getValue();
                if (child.isTextual()) {
                    String text = child.asText();
                    JsonNode replacement = resolvePlaceholder(text, instance, gatewayRequest);
                    if (replacement != null) {
                        obj.set(entry.getKey(), replacement);
                    }
                } else {
                    replacePlaceholders(child, instance, gatewayRequest, rule);
                }
            });
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                JsonNode child = array.get(i);
                if (child.isTextual()) {
                    String text = child.asText();
                    JsonNode replacement = resolvePlaceholder(text, instance, gatewayRequest);
                    if (replacement != null) {
                        array.set(i, replacement);
                    }
                } else {
                    replacePlaceholders(child, instance, gatewayRequest, rule);
                }
            }
        }
    }
    
    /**
     * 解析占位符
     */
    private JsonNode resolvePlaceholder(String text, ModelInstance instance, JsonNode gatewayRequest) {
        if (text == null || !text.startsWith("$")) return null;
        
        switch (text) {
            case "$model":
                String model = gatewayRequest.has("model") 
                    ? gatewayRequest.get("model").asText() 
                    : instance.getModelName();
                return TextNode.valueOf(model);
            case "$messages":
                if (gatewayRequest.has("messages")) {
                    return gatewayRequest.get("messages").deepCopy();
                }
                return objectMapper.createArrayNode();
            case "$message":
                // 提供与 OpenApiRequestConverter 相同的语义：提取“最后一条 user 消息”的 content，作为纯文本。
                // 这样模板可以直接写 "input": "$message" 来满足像 Ark 这类要求 input 为字符串的接口。
                String userMessage = extractUserMessage(gatewayRequest);
                return TextNode.valueOf(userMessage != null ? userMessage : "");
            case "$stream":
                if (gatewayRequest.has("stream")) {
                    return gatewayRequest.get("stream");
                }
                return objectMapper.getNodeFactory().booleanNode(true);
            default:
                // 支持 $field 格式，从 gatewayRequest 中读取
                if (text.length() > 1) {
                    String fieldName = text.substring(1);
                    if (gatewayRequest.has(fieldName)) {
                        return gatewayRequest.get(fieldName).deepCopy();
                    }
                }
                return null;
        }
    }

    /**
     * 从标准化后的网关请求中提取用户输入文本
     * 与 OpenApiRequestConverter.extractUserMessage 保持一致语义，避免两套逻辑不一致。
     */
    private String extractUserMessage(JsonNode gatewayRequest) {
        if (gatewayRequest == null) {
            return "";
        }
        // 优先从 messages 数组中提取最后一条 user 消息的 content
        if (gatewayRequest.has("messages") && gatewayRequest.get("messages").isArray()) {
            ArrayNode messages = (ArrayNode) gatewayRequest.get("messages");
            for (int i = messages.size() - 1; i >= 0; i--) {
                JsonNode msg = messages.get(i);
                if (msg.has("role") && "user".equals(msg.get("role").asText())) {
                    if (msg.has("content")) {
                        return msg.get("content").asText();
                    }
                }
            }
        }
        // 兼容旧格式：直接使用 message 字段
        if (gatewayRequest.has("message")) {
            return gatewayRequest.get("message").asText();
        }
        return "";
    }
    
    /**
     * 应用值转换
     */
    private void applyTransformations(ObjectNode result, JsonNode transformations) {
        if (transformations == null || !transformations.isObject()) {
            return;
        }
        
        transformations.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            String transformType = entry.getValue().asText();
            
            if (!result.has(fieldName)) {
                return;
            }
            
            JsonNode fieldValue = result.get(fieldName);
            JsonNode transformed = applyTransformation(fieldValue, transformType);
            if (transformed != null) {
                result.set(fieldName, transformed);
            }
        });
    }
    
    /**
     * 应用单个转换
     */
    private JsonNode applyTransformation(JsonNode value, String transformType) {
        if (value == null) {
            return null;
        }
        
        switch (transformType) {
            case "array_to_string":
                if (value.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode item : value) {
                        if (item.isTextual()) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(item.asText());
                        }
                    }
                    return TextNode.valueOf(sb.toString());
                }
                break;
            case "trim":
                if (value.isTextual()) {
                    return TextNode.valueOf(value.asText().trim());
                }
                break;
            default:
                break;
        }
        
        return value;
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
        // 如果实例配置了转换规则，则支持
        String ruleJson = instance.getRequestConversionRule();
        return ruleJson != null && !ruleJson.isBlank();
    }
}
