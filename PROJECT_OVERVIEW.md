# MoonCell-ModelHub 项目概况

## 1. 项目定位

`MoonCell-ModelHub` 是一个基于 Spring Boot WebFlux 的 AI 模型网关，核心目标是将多家模型服务商的接口统一到一个入口，并提供以下能力：

- 统一聊天入口（兼容流式 SSE 输出）
- 多实例负载均衡与并发控制
- 基于 Redis 的幂等控制
- 实例健康检查与管理后台
- 可视化监控页和调试页

主入口接口为：`POST /v1/chat/completions`。

## 2. 技术栈与依赖

- Java 17
- Spring Boot 3.2.2
- Spring Cloud 2023.0.0
- Spring WebFlux（响应式流式转发）
- MyBatis（实例与服务商配置持久化）
- MySQL（配置数据存储）
- Redis（幂等控制）
- Thymeleaf（管理页面）
- springdoc-openapi（Swagger UI）
- Nacos Discovery（服务发现）

项目构建工具：Maven（`pom.xml`）。

## 3. 目录与模块职责

- `src/main/java/com/mooncell/gateway/MoonCellGatewayApplication.java`
  - 启动类，启用服务发现与定时任务。
- `src/main/java/com/mooncell/gateway/web`
  - `GatewayController`：统一聊天接口。
  - `AdminController`：实例/服务商管理与监控数据接口。
  - `PageController`：页面路由（`/config`、`/admin/debug`）。
- `src/main/java/com/mooncell/gateway/service`
  - `GatewayService`：幂等、选路、下游请求组装、SSE 转换。
  - `AdminService`：实例与服务商管理逻辑。
  - `HeartbeatService`：实例心跳探测。
- `src/main/java/com/mooncell/gateway/core`
  - `balancer/LoadBalancer`：实例池管理与调度（随机采样 + 最少占用）。
  - `model/ModelInstance`：模型实例及运行时状态。
  - `dao/ModelInstanceMapper`：数据库访问层。
  - 另有 `lock` / `rate` 相关资源与限流组件（部分当前链路未直接使用）。
- `src/main/resources`
  - `application.properties`：端口、MySQL、Redis、Nacos、Swagger 配置。
  - `schema.sql`：`provider`、`model_instance`、`chat_task` 等表结构。
  - `templates/config.html`：监控与配置页面。
  - `templates/debug.html`：在线调试页面。

## 4. 核心请求流程（聊天接口）

1. 接收 `message` 与可选 `idempotencyKey`。
2. 若未传幂等键则生成 UUID。
3. 使用 Redis Lua 脚本做原子幂等检查（5 分钟 TTL）。
4. 从 `LoadBalancer` 获取可用实例（同时受健康状态、并发、令牌限制影响）。
5. 按实例配置组装下游请求体（支持 `$model`、`$messages` 占位符）。
6. 通过 `WebClient` 调用下游模型接口，消费 SSE。
7. 将不同服务商 SSE 归一为统一输出字段（`requestId`、`content`、`seq`），或按配置透传原始输出。
8. 请求结束后释放实例并发占用并清理幂等键。

## 5. 管理与可视化能力

管理 API 前缀：`/admin`

- 实例监控：
  - `GET /admin/healthy-instances`
  - `GET /admin/instance-stats`
  - `GET /admin/refresh-instances`
- 实例配置：
  - `GET /admin/instances`
  - `POST /admin/instances`
  - `PUT /admin/instances/{id}`
  - `PUT /admin/instances/{id}/post-model`
- 服务商配置：
  - `GET /admin/providers`
  - `POST /admin/providers`
  - `PUT /admin/providers/{id}`

页面入口：

- `GET /config`：监控 + 实例配置 + 服务商管理
- `GET /admin/debug`：统一聊天接口调试台

Swagger：

- `GET /swagger-ui.html`
- `GET /v3/api-docs`

## 6. 数据模型概览

`provider`：模型服务商信息（名称唯一）。

`model_instance`：模型实例配置，关键字段包括：

- `provider_id`、`model_name`、`url`、`api_key`
- `post_model`（JSON 模板）
- `response_request_id_path` / `response_content_path` / `response_seq_path`
- `response_raw_enabled`
- `max_qps`、`is_active`

`chat_task`：任务持久化表（目前在主流程中未见明显使用）。

## 7. 运行与本地启动

默认端口：`9061`。

运行前需要准备：

- MySQL（默认 `mooncell` 库，账号密码 `root/root`）
- Redis（默认 `localhost:6379`）
- Nacos（默认 `127.0.0.1:8848`，如不使用可按需调整配置）

启动命令：

```bash
mvn spring-boot:run
```

访问：

- 网关接口：`http://localhost:9061/v1/chat/completions`
- 管控页面：`http://localhost:9061/config`
- 调试页面：`http://localhost:9061/admin/debug`
- Swagger：`http://localhost:9061/swagger-ui.html`

## 8. 当前观察到的特点

- 已支持多服务商实例和响应字段路径映射，适配能力较强。
- 管理端可直接维护实例模板，便于快速接入新模型端点。
- 负载均衡与幂等控制逻辑集中在服务层，主链路清晰。
- 代码中存在若干保留组件与注释（例如部分 lock/rate 组件），后续可进一步梳理“在用/未用”边界。
