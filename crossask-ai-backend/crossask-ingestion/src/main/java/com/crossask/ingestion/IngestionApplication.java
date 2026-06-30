package com.crossask.ingestion;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@SpringBootApplication
@ComponentScan(basePackages = {"com.crossask.common", "com.crossask.ingestion"})
@MapperScan("com.crossask.common.mapper")
public class IngestionApplication {

    public static void main(String[] args) {
        // v0.7：库 crossask 与表 products 由 db-init/V0_7__products.sql 手动执行预创建，
        // 不再在应用启动时自动建库建表，避免依赖 CREATE 权限
        SpringApplication app = new SpringApplication(IngestionApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }

    /** 自动填充 createdAt / updatedAt（基于 MyBatis-Plus 3.5.9 路径）。 */
    @Component
    public static class TimestampFiller implements MetaObjectHandler {
        @Override
        public void insertFill(MetaObject metaObject) {
            LocalDateTime now = LocalDateTime.now();
            this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
            this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        }
        @Override
        public void updateFill(MetaObject metaObject) {
            this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        }
    }
}
