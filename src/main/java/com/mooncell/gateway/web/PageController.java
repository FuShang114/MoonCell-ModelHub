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
     * 健康节点监控页面
     */
    @GetMapping("/config")
    public String configPage(Model model) {
        return "config";
    }

    /**
     * 调试页面
     */
    @GetMapping("/admin/debug")
    public String debugPage(Model model) {
        return "debug";
    }
}