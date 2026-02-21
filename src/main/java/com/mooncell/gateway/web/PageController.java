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