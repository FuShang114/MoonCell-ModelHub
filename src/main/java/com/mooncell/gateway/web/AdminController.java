package com.mooncell.gateway.web;

import com.mooncell.gateway.dto.HealthyInstanceDto;
import com.mooncell.gateway.dto.InstanceConfigDto;
import com.mooncell.gateway.dto.InstanceStatsDto;
import com.mooncell.gateway.dto.AddInstanceRequest;
import com.mooncell.gateway.dto.LoadBalancingSettingsDto;
import com.mooncell.gateway.dto.MonitorMetricsDto;
import com.mooncell.gateway.dto.ProviderDto;
import com.mooncell.gateway.dto.ProviderRequest;
import com.mooncell.gateway.dto.StrategyStatusDto;
import com.mooncell.gateway.dto.UpdatePostModelRequest;
import com.mooncell.gateway.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Tag(name = "管理控制器", description = "系统监控和服务实例管理接口")
public class AdminController {

    private final AdminService adminService;

    /**
     * 获取健康实例列表 - 用于前端配置页面显示
     */
    @Operation(
        summary = "获取健康实例列表",
        description = "获取所有健康状态的AI模型服务实例列表，用于前端监控页面显示"
    )
    @GetMapping("/healthy-instances")
    public List<HealthyInstanceDto> getHealthyInstances() {
        return adminService.getHealthyInstances();
    }

    /**
     * 获取实例统计信息 - 用于前端配置页面显示
     */
    @Operation(
        summary = "获取实例统计信息",
        description = "获取LoadBalancer的实例统计信息，包括总实例数、健康实例数和可用RPM/TPM"
    )
    @GetMapping("/instance-stats")
    public InstanceStatsDto getInstanceStats() {
        return adminService.getInstanceStats();
    }

    /**
     * 刷新实例列表 - 用于前端配置页面
     */
    @Operation(
        summary = "刷新实例列表",
        description = "强制刷新LoadBalancer中的实例数据，通常在配置变更后调用"
    )
    @GetMapping("/refresh-instances")
    public String refreshInstances() {
        return adminService.refreshInstances();
    }

    /**
     * 添加AI模型服务实例 - 管理员功能
     */
    @Operation(
        summary = "添加AI模型服务实例",
        description = "注册新的AI模型服务实例到系统中，支持动态扩缩容",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "服务实例注册请求",
            content = @Content(schema = @Schema(implementation = AddInstanceRequest.class))
        )
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "成功添加实例"),
        @ApiResponse(responseCode = "500", description = "系统错误或非法服务商")
    })
    @PostMapping("/instances")
    public String addInstance(@Parameter(description = "服务实例注册请求") @RequestBody AddInstanceRequest request) throws Exception {
        return adminService.addInstance(request);
    }

    /**
     * 获取所有实例配置 - 用于前端配置页面编辑
     */
    @Operation(
        summary = "获取实例配置列表",
        description = "获取所有AI模型服务实例的配置数据，用于前端模板编辑"
    )
    @GetMapping("/instances")
    public List<InstanceConfigDto> getInstances() {
        return adminService.getInstances();
    }

    /**
     * 更新实例请求体模板
     */
    @Operation(
        summary = "更新实例请求体模板",
        description = "更新指定实例的 post_model JSON 模板"
    )
    @PutMapping("/instances/{id}/post-model")
    public String updatePostModel(
        @PathVariable("id") Long id,
        @RequestBody UpdatePostModelRequest request
    ) {
        return adminService.updatePostModel(id, request.getPostModel());
    }

    @Operation(
        summary = "更新实例信息",
        description = "更新实例的基础字段及请求体模板"
    )
    @PutMapping("/instances/{id}")
    public String updateInstance(
        @PathVariable("id") Long id,
        @RequestParam(value = "active", required = false) Boolean isActive,
        @RequestBody AddInstanceRequest request
    ) throws Exception {
        return adminService.updateInstance(id, request, isActive);
    }

    @Operation(summary = "获取服务商列表", description = "获取所有模型服务商")
    @GetMapping("/providers")
    public List<ProviderDto> getProviders() {
        return adminService.getProviders();
    }

    @Operation(summary = "新增服务商", description = "创建新的模型服务商")
    @PostMapping("/providers")
    public String addProvider(@RequestBody ProviderRequest request) {
        return adminService.addProvider(request);
    }

    @Operation(summary = "更新服务商", description = "更新服务商名称和描述")
    @PutMapping("/providers/{id}")
    public String updateProvider(@PathVariable("id") Long id, @RequestBody ProviderRequest request) {
        return adminService.updateProvider(id, request);
    }

    @Operation(summary = "获取负载均衡算法配置", description = "获取当前算法及相关参数")
    @GetMapping("/load-balancing/settings")
    public LoadBalancingSettingsDto getLoadBalancingSettings() {
        return adminService.getLoadBalancingSettings();
    }

    @Operation(summary = "更新负载均衡算法配置", description = "支持热切换传统算法和对象池算法")
    @PutMapping("/load-balancing/settings")
    public LoadBalancingSettingsDto updateLoadBalancingSettings(@RequestBody LoadBalancingSettingsDto request) {
        return adminService.updateLoadBalancingSettings(request);
    }

    @Operation(summary = "获取策略运行状态", description = "获取当前激活和排空中的策略状态列表")
    @GetMapping("/load-balancing/strategy-statuses")
    public List<StrategyStatusDto> getStrategyStatuses() {
        return adminService.getStrategyStatuses();
    }

    @Operation(summary = "获取监控时序指标", description = "用于监控大屏折线图展示 JVM、吞吐和资源使用趋势")
    @GetMapping("/monitor-metrics")
    public MonitorMetricsDto getMonitorMetrics() {
        return adminService.getMonitorMetrics();
    }

    @Operation(summary = "清零监控统计数据", description = "清零所有监控统计数据，包括请求计数、失败原因统计等")
    @PostMapping("/monitor-metrics/reset")
    public String resetMetrics() {
        return adminService.resetMetrics();
    }
}
