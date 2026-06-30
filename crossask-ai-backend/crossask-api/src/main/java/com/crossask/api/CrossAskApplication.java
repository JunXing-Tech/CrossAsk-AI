package com.crossask.api;

import com.crossask.api.chat.ChatMemoryProperties;
import com.crossask.api.product.ProductQueryProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(basePackages = {"com.crossask.common", "com.crossask.api"})
@MapperScan("com.crossask.common.mapper")
@EnableConfigurationProperties({ProductQueryProperties.class, ChatMemoryProperties.class})
@EnableScheduling
public class CrossAskApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrossAskApplication.class, args);
    }
}
