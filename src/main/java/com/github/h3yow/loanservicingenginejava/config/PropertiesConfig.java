package com.github.h3yow.loanservicingenginejava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用配置属性绑定。
 */
@Configuration
@EnableConfigurationProperties(ServicingProperties.class)
public class PropertiesConfig {
}
