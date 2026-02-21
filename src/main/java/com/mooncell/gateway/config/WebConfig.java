package com.mooncell.gateway.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Web配置类
 * 
 * <p>注意：现在每个实例都有专用的 WebClient 和连接池（由 InstanceWebClientManager 管理），
 * 因此不再需要全局的 WebClient Bean。
 * 
 * <p>连接池配置已迁移到 {@link com.mooncell.gateway.service.InstanceWebClientManager}，
 * 每个实例的连接池大小根据实例的 RPM 限制动态计算。
 */
@Configuration
public class WebConfig {
    // 全局 WebClient 配置已移除，现在使用实例专用的 WebClient
    // 每个实例的连接池由 InstanceWebClientManager 管理
}
