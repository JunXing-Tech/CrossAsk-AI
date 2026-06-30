# CrossAsk 后端镜像 —— 轻量部署：只载入已编译好的 fat jar 运行，不在容器内编译
# 构建前请先在本地执行：mvn -pl crossask-api -am clean package -DskipTests
# 然后把生成的 jar 复制到本目录（或用 --build-arg 指定路径），再 docker build

FROM eclipse-temurin:21-jre-jammy

# 时区设为上海（chat_history 定时清理 cron 依赖本地时区）
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

WORKDIR /app

# 已编译好的 fat jar（由 CI/本地 mvn package 产出后放到构建上下文）
ARG JAR_FILE=crossask-api/target/crossask-api-0.1.0-SNAPSHOT.jar
COPY ${JAR_FILE} app.jar

# 后端监听 8080（容器内端口；对外映射由 docker run -p 控制）
EXPOSE 8080

# JVM 内存与容器内存对齐；时区；可被 docker run 的 JAVA_OPTS 覆盖
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Shanghai"

# 所有敏感配置（API key / DB 密码）通过环境变量注入，不打进镜像
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
