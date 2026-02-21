package com.mooncell.gateway.core.converter.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.mooncell.gateway.core.converter.RequestConverter;
import com.mooncell.gateway.core.model.ModelInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 基于 OpenAPI Schema 的请求转换器
 * <p>
 * 支持两种模式：
 * 1. 基于 postModel 模板的转换（兼容现有逻辑）
 * 2. 基于 OpenAPI Schema 的智能转换（未来扩展）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenApiRequestConverter implements RequestConverter {
    
    private final ObjectMapper objectMapper;
    
    @Override
    public JsonNode convert(ModelInstance instance, JsonNode gatewayRequest) {
        // 如果实例配置了 postModel 模板，使用模板转换
        String template = instance.getPostModel();
        if (template != null && !template.isBlank()) {
            return convertByTemplate(instance, gatewayRequest, template);
        }
        
        // 否则使用默认转换逻辑
        return convertToDefaultFormat(instance, gatewayRequest);
    }
    
    /**
     * 基于模板的转换（兼容现有逻辑）
     */
    private JsonNode convertByTemplate(ModelInstance instance, JsonNode gatewayRequest, String template) {
        try {
            ObjectNode payload = parseTemplate(template);
            
            // 从标准格式提取数据
            String model = gatewayRequest.has("model") 
                ? gatewayRequest.get("model").asText() 
                : instance.getModelName();
            
            // 替换占位符（$model, $messages, $stream, $temperature 等 OpenAPI 入参）
            replacePlaceholders(payload, instance, gatewayRequest);
            
            return payload;
        } catch (Exception e) {
            log.warn("Failed to convert by template for instance {}, fallback to default: {}", 
                instance.getModelName(), e.getMessage());
            return convertToDefaultFormat(instance, gatewayRequest);
        }
    }
    
    /**
     * 默认格式转换
     */
    private JsonNode convertToDefaultFormat(ModelInstance instance, JsonNode gatewayRequest) {
        ObjectNode payload = objectMapper.createObjectNode();
        
        // 提取用户消息
        String userMessage = extractUserMessage(gatewayRequest);
        
        // 构建最小请求体
        payload.put("stream", true);
        payload.put("message", userMessage);
        payload.put("model", instance.getModelName());
        
        // 复制其他字段
        copyAdditionalFields(gatewayRequest, payload);
        
        return payload;
    }
    
    /**
     * 从标准格式中提取用户消息
     */
    private String extractUserMessage(JsonNode gatewayRequest) {
        // 优先从 messages 数组中提取最后一条 user 消息
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
     * 复制额外的字段（temperature, max_tokens 等）
     */
    private void copyAdditionalFields(JsonNode source, ObjectNode target) {
        String[] fieldsToCopy = {"temperature", "max_tokens", "maxTokens", "top_p", "topP", 
                                 "frequency_penalty", "frequencyPenalty", "presence_penalty", 
                                 "presencePenalty", "stop", "user"};
        
        for (String field : fieldsToCopy) {
            if (source.has(field)) {
                JsonNode value = source.get(field);
                if (value.isValueNode()) {
                    if (value.isTextual()) {
                        target.put(field, value.asText());
                    } else if (value.isNumber()) {
                        if (value.isInt()) {
                            target.put(field, value.asInt());
                        } else if (value.isDouble()) {
                            target.put(field, value.asDouble());
                        }
                    } else if (value.isBoolean()) {
                        target.put(field, value.asBoolean());
                    }
                } else if (value.isArray()) {
                    target.set(field, value.deepCopy());
                }
            }
        }
    }
    
    /**
     * 解析模板
     */
    private ObjectNode parseTemplate(String template) {
        try {
            JsonNode root = objectMapper.readTree(template);
            if (root != null && root.isObject()) {
                return (ObjectNode) root.deepCopy();
            }
        } catch (Exception e) {
            log.warn("Invalid post_model JSON template: {}", e.getMessage());
        }
        return objectMapper.createObjectNode();
    }
    
    /**
     * 从网关请求中获取字段值（支持 snake_case 与 camelCase）
     */
    private JsonNode getFromRequest(JsonNode gatewayRequest, String... keys) {
        for (String key : keys) {
            if (key != null && gatewayRequest.has(key)) {
                return gatewayRequest.get(key);
            }
        }
        return null;
    }

    private JsonNode copyNode(JsonNode node) {
        return node != null ? node.deepCopy() : null;
    }

    /**
     * 递归替换占位符（支持所有 OpenAPI ChatCompletion 入参）
     */
    private void replacePlaceholders(JsonNode node, ModelInstance instance, JsonNode gatewayRequest) {
        if (node == null) return;
        String model = gatewayRequest.has("model")
                ? gatewayRequest.get("model").asText()
                : instance.getModelName();
        JsonNode messagesNode = getFromRequest(gatewayRequest, "messages");

        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            var fields = obj.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                JsonNode child = entry.getValue();
                if (child.isTextual()) {
                    String text = child.asText();
                    JsonNode replacement = resolvePlaceholder(text, model, messagesNode, gatewayRequest);
                    if (replacement != null) {
                        obj.set(entry.getKey(), replacement);
                    } else {
                        replacePlaceholders(child, instance, gatewayRequest);
                    }
                } else {
                    replacePlaceholders(child, instance, gatewayRequest);
                }
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                JsonNode child = array.get(i);
                if (child.isTextual()) {
                    String text = child.asText();
                    JsonNode replacement = resolvePlaceholder(text, model, messagesNode, gatewayRequest);
                    if (replacement != null) {
                        array.set(i, replacement);
                    } else {
                        replacePlaceholders(child, instance, gatewayRequest);
                    }
                } else {
                    replacePlaceholders(child, instance, gatewayRequest);
                }
            }
        }
    }

    /**
     * 解析占位符并返回替换值，不支持则返回 null
     */
    private JsonNode resolvePlaceholder(String text, String model, JsonNode messagesNode, JsonNode gatewayRequest) {
        if (text == null || !text.startsWith("$")) return null;
        switch (text) {
            case "$model":
                return TextNode.valueOf(model);
            case "$messages":
                if (messagesNode != null) return messagesNode.deepCopy();
                return objectMapper.createArrayNode();
            case "$stream":
                JsonNode s = getFromRequest(gatewayRequest, "stream");
                return s != null ? s : BooleanNode.valueOf(true);
            case "$temperature":
                return copyNode(getFromRequest(gatewayRequest, "temperature"));
            case "$max_tokens":
                return copyNode(getFromRequest(gatewayRequest, "max_tokens", "maxTokens"));
            case "$top_p":
                return copyNode(getFromRequest(gatewayRequest, "top_p", "topP"));
            case "$frequency_penalty":
                return copyNode(getFromRequest(gatewayRequest, "frequency_penalty", "frequencyPenalty"));
            case "$presence_penalty":
                return copyNode(getFromRequest(gatewayRequest, "presence_penalty", "presencePenalty"));
            case "$user":
                return copyNode(getFromRequest(gatewayRequest, "user"));
            case "$idempotency_key":
                return copyNode(getFromRequest(gatewayRequest, "idempotency_key", "idempotencyKey"));
            case "$stop":
                return copyNode(getFromRequest(gatewayRequest, "stop"));
            case "$extra":
                JsonNode extra = getFromRequest(gatewayRequest, "extra");
                return extra != null ? extra.deepCopy() : objectMapper.createObjectNode();
            default:
                return null;
        }
    }
    
    @Override
    public boolean supports(ModelInstance instance) {
        // 默认支持所有实例
        return true;
    }
}
