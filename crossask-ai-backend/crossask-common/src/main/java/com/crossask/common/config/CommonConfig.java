package com.crossask.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({QdrantProperties.class, RagProperties.class, HybridProperties.class})
public class CommonConfig {
}
