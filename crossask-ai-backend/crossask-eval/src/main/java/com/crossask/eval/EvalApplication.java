package com.crossask.eval;

import com.crossask.eval.config.EvalProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/**
 * v0.8 Eval 模块主类。
 * 只扫描 com.crossask.eval（不扫 common 的 Bean——eval 只用 common 的 model 类，不需要 DataSource/embedding 等）。
 * 排除 MybatisPlusAutoConfiguration 双保险。
 */
@SpringBootApplication(exclude = {
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration.class
})
@ComponentScan(basePackages = {"com.crossask.eval"})
@EnableConfigurationProperties(EvalProperties.class)
public class EvalApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(EvalApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }
}
