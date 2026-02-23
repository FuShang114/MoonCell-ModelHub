package com.mooncell.gateway.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面控制器
 * 负责处理HTML页面路由
 */
@Controller
public class PageController {

    /**
     * 配置页面
     * @param model 模型对象
     * @return 配置页面模板名称
     */
    @GetMapping("/config")
    public String configPage(Model model) {
        return "config";
    }

    @GetMapping("/admin/instances-ui")
    public String instancesPage(Model model) {
        return "instances";
    }

    @GetMapping("/admin/providers-ui")
    public String providersPage(Model model) {
        // 为了兼容历史链接，将服务商管理与实例管理统一到同一页面
        return "instances";
    }

    /**
     * 调试页面
     * @param model 模型对象
     * @return 调试页面模板名称
     */
    @GetMapping("/admin/debug")
    public String debugPage(Model model) {
        return "debug";
    }

    /**
     * 设置页面
     * @param model 模型对象
     * @return 设置页面模板名称
     */
    @GetMapping("/settings")
    public String settingsPage(Model model) {
        return "settings";
    }
}