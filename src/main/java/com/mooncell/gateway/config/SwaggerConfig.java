package com.mooncell.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger配置类
 * 配置API文档生成
 */
@Configuration
public class SwaggerConfig {

    /**
     * 配置OpenAPI文档信息
     * @return OpenAPI配置对象
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("MoonCell Gateway API")
                        .description("MoonCell AI模型统一访问网关，提供负载均衡、幂等控制和实时流式响应")
                        .version("v1.0")
                        .contact(new Contact()
                                .name("MoonCell Team")
                                .email("support@mooncell.com")
                                .url("https://github.com/mooncell/gateway"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("http://springdoc.org")));
    }
}