# 转换器架构设计文档

## 概述

本架构实现了基于 OpenAPI 格式的请求/响应转换系统，支持将网关统一的标准格式转换为各个模型实例特定的格式。

## 核心组件

### 1. 标准格式定义

#### ChatCompletionRequest
标准的 OpenAI ChatCompletion 请求格式，包含：
- `model`: 模型名称
- `messages`: 消息列表（支持多轮对话）
- `stream`: 是否流式输出
- `temperature`, `max_tokens`, `top_p` 等参数
- `idempotency_key`: 幂等键

#### ChatCompletionResponse
标准的 OpenAI ChatCompletion 响应格式，包含：
- `id`: 响应 ID
- `object`: 对象类型
- `choices`: 选择列表
- `usage`: Token 使用情况

### 2. 转换器接口

#### RequestConverter
负责将网关统一请求转换为实例特定格式。

```java
JsonNode convert(ModelInstance instance, JsonNode gatewayRequest);
boolean supports(ModelInstance instance);
```

#### ResponseConverter
负责将实例响应转换为网关统一格式。

```java
List<String> convert(ModelInstance instance, JsonNode instanceResponse, 
                     String defaultRequestId, AtomicInteger seqCounter);
boolean supports(ModelInstance instance);
```

### 3. 转换器实现

#### OpenApiRequestConverter
- **模板模式**：基于 `postModel` 模板转换（兼容现有逻辑）
- **默认模式**：构建最小请求体
- **占位符支持**：`$model`, `$messages`
- **字段复制**：自动复制 `temperature`, `max_tokens` 等参数

#### OpenApiResponseConverter
- **Raw 模式**：直接返回原始响应
- **路径映射模式**：使用 JSONPath 提取字段并转换为标准格式
- **字段映射**：`responseRequestIdPath`, `responseContentPath`, `responseSeqPath`

#### SseResponseConverter
专门处理 SSE 流式响应：
- 规范化 SSE 数据（去除 `data:` 前缀）
- 处理 `[DONE]` 标记
- 调用响应转换器进行格式转换

### 4. 转换器工厂

#### ConverterFactory
- **转换器管理**：自动发现和注册所有转换器
- **缓存机制**：缓存实例对应的转换器，提高性能
- **缓存清理**：支持清除缓存（实例配置更新时）

## 使用方式

### 1. 请求转换

```java
// GatewayService 中使用
ObjectNode payload = buildPayloadWithConverter(instance, request);
```

### 2. 响应转换

```java
// SSE 流式响应转换
List<String> outputs = sseResponseConverter.convertSseChunk(
    chunk, instance, defaultRequestId, seqCounter);
```

### 3. 向后兼容

- 旧的 `OpenAiRequest` 格式会自动转换为标准格式
- `buildPayload` 方法标记为 `@Deprecated`，但仍可使用
- `convertSseChunk` 方法内部调用新的转换器

## 配置说明

### 实例配置字段

#### 请求转换配置
- `postModel`: JSON 模板字符串，支持占位符 `$model` 和 `$messages`

#### 响应转换配置
- `responseRawEnabled`: 是否启用原始输出模式
- `responseRequestIdPath`: 响应 ID 的 JSONPath（如 `"id"`）
- `responseContentPath`: 响应内容的 JSONPath（如 `"choices.0.delta.content"`）
- `responseSeqPath`: 响应序号的 JSONPath（如 `"choices.0.index"`）

## 扩展性

### 添加新的转换器

1. 实现 `RequestConverter` 或 `ResponseConverter` 接口
2. 添加 `@Component` 注解
3. 在 `supports` 方法中定义支持的实例条件
4. Spring 会自动发现并注册

### 示例：自定义转换器

```java
@Component
public class CustomRequestConverter implements RequestConverter {
    @Override
    public JsonNode convert(ModelInstance instance, JsonNode gatewayRequest) {
        // 自定义转换逻辑
    }
    
    @Override
    public boolean supports(ModelInstance instance) {
        return "custom-provider".equals(instance.getProviderName());
    }
}
```

## 性能优化

1. **转换器缓存**：每个实例的转换器会被缓存，避免重复查找
2. **减少 JSON 解析**：优化 SSE 数据规范化，减少字符串操作
3. **流式处理**：支持流式响应转换，不阻塞主流程

## 未来扩展

1. **OpenAPI Schema 支持**：基于实例的 OpenAPI Schema 自动生成转换规则
2. **转换规则配置**：支持更灵活的 JSONPath 映射配置
3. **转换器链**：支持多个转换器链式调用
4. **转换器插件**：支持动态加载转换器插件
