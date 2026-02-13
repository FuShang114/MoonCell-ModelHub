package com.mooncell.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

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