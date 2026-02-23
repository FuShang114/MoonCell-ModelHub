# ModelInstance 转换方法设计文档

## 一、架构理解

### 1.1 当前架构概览

MoonCell ModelHub 是一个 AI 模型网关系统，核心功能是作为统一入口，将标准的 OpenAPI 格式请求转换为各个服务商特定的请求格式，并将服务商的响应转换回标准的 OpenAPI 格式。

#### 核心组件

1. **ModelInstance（模型实例）**
   - 代表一个可用的大模型服务节点（URL 级别）
   - 包含配置信息：URL、API Key、模型名称、转换规则等
   - 存储运行时状态：失败计数、请求计数、熔断状态等

2. **转换器架构**
   - **RequestConverter**：将 OpenAPI 格式转换为实例特定格式
   - **ResponseConverter**：将实例响应转换为 OpenAPI 格式
   - **ConverterFactory**：管理和缓存转换器实例

3. **转换器实现**
   - **RuleBasedRequestConverter**：基于转换规则的请求转换器
   - **RuleBasedResponseConverter**：基于转换规则的响应转换器
   - **OpenApiRequestConverter**：默认的 OpenAPI 请求转换器（基于 postModel 模板）
   - **OpenApiResponseConverter**：默认的 OpenAPI 响应转换器
   - **SseResponseConverter**：SSE 流式响应转换器

4. **GatewayService（网关服务）**
   - 接收 OpenAPI 格式请求
   - 通过 LoadBalancer 选择可用实例
   - 使用转换器转换请求格式
   - 通过 WebClient 发送请求到下游服务
   - 将 SSE 流式响应转换为统一格式返回

### 1.2 数据流转过程

```
前端请求 (OpenAPI 格式)
    ↓
GatewayController
    ↓
GatewayService.chat()
    ↓
[1] 幂等控制
[2] 负载均衡选择实例 (ModelInstance)
[3] 请求转换: OpenAPI → 实例格式
    - 使用 RequestConverter.convert(instance, openApiRequest)
    - 返回 ObjectNode（可直接序列化为 JSON）
    ↓
[4] WebClient 发送请求
    - instanceWebClient.post()
    - .uri(instance.getUrl())
    - .bodyValue(convertedRequest)
    - .bodyToFlux(String.class)  // SSE 流式响应
    ↓
[5] 响应转换: 实例格式 → OpenAPI SSE 格式
    - SseResponseConverter.convertSseChunk()
    - 内部调用 ResponseConverter.convert()
    - 返回 List<String>（每个字符串是一个 SSE 数据块）
    ↓
前端接收 (OpenAPI SSE 格式)
```

### 1.3 转换规则机制

#### 请求转换规则（requestConversionRule）

存储在 `ModelInstance.requestConversionRule` 字段中，JSON 字符串格式：

```json
{
  "type": "template",  // 或 "mapping"
  "template": {
    "model": "$model",
    "messages": "$messages",
    "stream": "$stream",
    "temperature": "$temperature"
  },
  "mapping": {
    "model": "model",
    "input": "messages",
    "stream": "stream"
  },
  "transformations": {
    "messages": "array_to_string"
  }
}
```

支持的占位符：
- `$model`：模型名称
- `$messages`：消息数组
- `$stream`：流式标志
- `$field`：从 OpenAPI 请求中读取字段

#### 响应转换规则（responseConversionRule）

存储在 `ModelInstance.responseConversionRule` 字段中，JSON 字符串格式：

```json
{
  "type": "mapping",
  "requestIdPath": "id",
  "contentPath": "choices.0.delta.content",
  "seqPath": "choices.0.index",
  "transformations": {
    "content": "trim"
  }
}
```

或模板模式：

```json
{
  "type": "template",
  "template": {
    "id": "$requestId",
    "object": "$object",
    "model": "$model",
    "choices": [{
      "index": "$seq",
      "delta": {
        "content": "$content"
      }
    }]
  }
}
```

### 1.4 WebClient 交互

- **InstanceWebClientManager**：为每个实例管理独立的 WebClient 和连接池
- **连接池配置**：根据实例的 RPM 限制动态计算连接数
- **请求发送**：
  ```java
  WebClient instanceWebClient = instanceWebClientManager.getWebClient(instance);
  instanceWebClient.post()
      .uri(instance.getUrl())
      .headers(headers -> {
          headers.setBearerAuth(instance.getApiKey());
          // ... 其他头部
      })
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue(payload)  // ObjectNode，已转换的请求体
      .retrieve()
      .bodyToFlux(String.class)  // SSE 流式响应
  ```

### 1.5 SSE 响应处理

- **SseResponseConverter**：处理 SSE 格式的流式响应
- **流程**：
  1. 接收原始 SSE 数据块（可能包含多行）
  2. 分割为单行
  3. 提取 `data:` 后的 JSON payload
  4. 解析 JSON 为 JsonNode
  5. 调用 ResponseConverter 转换为 OpenAPI 格式
  6. 返回 List<String>（每个字符串是一个 SSE 数据块）

## 二、改造需求分析

### 2.1 需求描述

在 `ModelInstance` 类中提供两个 `convert` 方法：

1. **请求转换方法**：`convertRequest(JsonNode openApiRequest)`
   - 输入：OpenAPI 格式的请求（JsonNode）
   - 输出：实例特定格式的请求（ObjectNode，可直接用于 WebClient）

2. **响应转换方法**：`convertResponse(JsonNode instanceResponse, String requestId, AtomicInteger seqCounter)`
   - 输入：实例原始响应（JsonNode）、请求 ID、序号计数器
   - 输出：OpenAPI SSE 格式的响应（List<String>）

### 2.2 设计考虑

#### 问题 1：依赖注入

`ModelInstance` 是实体类（POJO），不应该直接依赖 Spring Bean（如 ConverterFactory）。

**解决方案**：
- 方案 A：通过方法参数传入转换器（推荐）
  - `convertRequest(JsonNode openApiRequest, RequestConverter converter)`
  - `convertResponse(JsonNode instanceResponse, String requestId, AtomicInteger seqCounter, ResponseConverter converter)`
- 方案 B：在 ModelInstance 中持有转换器引用（不推荐，违反实体类设计原则）

#### 问题 2：SSE 响应处理

响应转换需要考虑 SSE 格式的特殊处理：
- 原始响应可能是单个 JSON 对象
- 也可能是 SSE 格式的字符串（包含 `data:` 前缀）
- 需要统一处理为 OpenAPI SSE 格式

**解决方案**：
- 响应转换方法内部处理 SSE 格式
- 或者提供两个方法：`convertResponse()` 和 `convertSseResponse()`

#### 问题 3：与现有代码的兼容性

需要确保改造不影响现有的 `GatewayService` 等代码。

**解决方案**：
- 保持现有转换器接口不变
- ModelInstance 的转换方法内部调用转换器
- 逐步迁移现有代码使用 ModelInstance 的转换方法

## 三、改造计划

### 3.1 阶段一：在 ModelInstance 中添加转换方法

#### 3.1.1 添加请求转换方法

```java
/**
 * 将 OpenAPI 格式请求转换为实例特定格式
 * 
 * @param openApiRequest OpenAPI 格式请求（JsonNode）
 * @param requestConverter 请求转换器（由 ConverterFactory 提供）
 * @return 实例特定格式的请求体（ObjectNode，可直接用于 WebClient）
 */
public ObjectNode convertRequest(JsonNode openApiRequest, RequestConverter requestConverter) {
    if (openApiRequest == null) {
        throw new IllegalArgumentException("OpenAPI request cannot be null");
    }
    if (requestConverter == null) {
        throw new IllegalArgumentException("Request converter cannot be null");
    }
    
    JsonNode converted = requestConverter.convert(this, openApiRequest);
    
    // 确保返回 ObjectNode
    if (converted instanceof ObjectNode) {
        return (ObjectNode) converted;
    } else if (converted.isObject()) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode result = mapper.createObjectNode();
        converted.fields().forEachRemaining(entry -> 
            result.set(entry.getKey(), entry.getValue()));
        return result;
    } else {
        throw new IllegalStateException("Converted request is not an object");
    }
}
```

#### 3.1.2 添加响应转换方法

```java
/**
 * 将实例响应转换为 OpenAPI SSE 格式
 * 
 * @param instanceResponse 实例原始响应（JsonNode）
 * @param requestId 请求 ID（用于填充缺失字段）
 * @param seqCounter 序号计数器（用于生成序号）
 * @param responseConverter 响应转换器（由 ConverterFactory 提供）
 * @return OpenAPI SSE 格式的响应列表（每个字符串是一个 SSE 数据块）
 */
public List<String> convertResponse(JsonNode instanceResponse, 
                                   String requestId, 
                                   AtomicInteger seqCounter,
                                   ResponseConverter responseConverter) {
    if (responseConverter == null) {
        throw new IllegalArgumentException("Response converter cannot be null");
    }
    
    if (instanceResponse == null) {
        return new ArrayList<>();
    }
    
    return responseConverter.convert(this, instanceResponse, requestId, seqCounter);
}
```

#### 3.1.3 添加 SSE 响应转换方法（可选）

如果需要直接处理 SSE 字符串：

```java
/**
 * 将 SSE 格式的实例响应转换为 OpenAPI SSE 格式
 * 
 * @param sseChunk SSE 格式的原始响应（可能包含多行）
 * @param requestId 请求 ID
 * @param seqCounter 序号计数器
 * @param sseResponseConverter SSE 响应转换器
 * @return OpenAPI SSE 格式的响应列表
 */
public List<String> convertSseResponse(String sseChunk,
                                      String requestId,
                                      AtomicInteger seqCounter,
                                      SseResponseConverter sseResponseConverter) {
    if (sseResponseConverter == null) {
        throw new IllegalArgumentException("SSE response converter cannot be null");
    }
    
    if (sseChunk == null || sseChunk.isEmpty()) {
        return new ArrayList<>();
    }
    
    return sseResponseConverter.convertSseChunk(sseChunk, this, requestId, seqCounter);
}
```

### 3.2 阶段二：更新 GatewayService 使用新方法

#### 3.2.1 更新 buildPayloadWithConverter 方法

```java
public ObjectNode buildPayloadWithConverter(ModelInstance instance, Object request) {
    // 将请求转换为标准格式（JsonNode）
    JsonNode gatewayRequest = convertToStandardFormat(request, instance);
    
    // 获取请求转换器
    RequestConverter requestConverter = converterFactory.getRequestConverter(instance);
    
    // 使用 ModelInstance 的转换方法
    return instance.convertRequest(gatewayRequest, requestConverter);
}
```

#### 3.2.2 更新 SSE 响应处理

```java
// 在 chat() 方法中
.flatMap(chunk -> {
    // 获取响应转换器
    ResponseConverter responseConverter = converterFactory.getResponseConverter(finalInstance);
    
    // 使用 SseResponseConverter 处理 SSE 块
    List<String> converted = sseResponseConverter.convertSseChunk(
        chunk, finalInstance, finalIdempotencyKey, seqCounter);
    
    return Flux.fromIterable(converted);
})
```

### 3.3 阶段三：优化和测试

1. **单元测试**：为 ModelInstance 的转换方法编写单元测试
2. **集成测试**：测试与 GatewayService 的集成
3. **性能测试**：确保转换性能不受影响
4. **文档更新**：更新相关文档

### 3.4 阶段四：逐步迁移（可选）

1. 保持现有转换器接口不变，确保向后兼容
2. 逐步将其他使用转换器的地方迁移到使用 ModelInstance 的方法
3. 最终可以考虑将转换器逻辑完全封装在 ModelInstance 中

## 四、实现细节

### 4.1 依赖处理

由于 `ModelInstance` 是实体类，不能直接注入 Spring Bean，需要：

1. **在方法参数中传入转换器**（推荐）
2. **使用静态工厂方法**（不推荐，违反 OOP 原则）
3. **使用 ThreadLocal 或上下文传递**（复杂，不推荐）

### 4.2 错误处理

转换方法应该：
- 验证输入参数
- 处理转换异常
- 提供有意义的错误信息

### 4.3 性能考虑

- 转换器已经通过 ConverterFactory 缓存
- ModelInstance 的转换方法只是简单的委托，性能开销可忽略
- 保持现有的路径缓存等优化机制

### 4.4 与 WebClient 的集成

转换后的 `ObjectNode` 可以直接用于 WebClient：

```java
ObjectNode payload = instance.convertRequest(openApiRequest, requestConverter);
webClient.post()
    .uri(instance.getUrl())
    .bodyValue(payload)  // 直接使用
    .retrieve()
    .bodyToFlux(String.class)
```

## 五、总结

### 5.1 设计优势

1. **封装性**：转换逻辑封装在 ModelInstance 中，使用更直观
2. **可测试性**：转换方法可以独立测试
3. **向后兼容**：保持现有转换器接口不变
4. **灵活性**：通过方法参数传入转换器，保持实体类的纯净性

### 5.2 注意事项

1. ModelInstance 不能直接依赖 Spring Bean，需要通过方法参数传入
2. 保持与现有代码的兼容性
3. 确保错误处理和性能不受影响
4. 逐步迁移，不要一次性改动太大

### 5.3 后续优化方向

1. 考虑将转换器逻辑完全封装在 ModelInstance 中
2. 支持更复杂的转换规则
3. 提供转换规则的验证和测试工具
4. 支持转换规则的动态更新
