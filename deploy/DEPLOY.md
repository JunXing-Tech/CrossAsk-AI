# CrossAsk 部署文档

轻量部署方案：后端 Docker 容器（载入预编译 jar），MySQL/Qdrant 用服务器已有实例，前端 dist 由 Nginx 托管。

## 架构

```
                          服务器
浏览器 ──HTTPS:18735──►  Nginx
                          ├─ /        → /www/wwwroot/crossask-ai/crossask-ai-frontend/dist（Vue 静态）
                          └─ /api/*   → 127.0.0.1:8080（去掉 /api 前缀）
                                            │
                          Docker 容器 ───────┘
                            └─ crossask-api（fat jar，监听 8080，仅内部）
                          MySQL 5.7（已有，101.96.211.131:3306）
                          Qdrant（已有，101.96.211.131:6333）
```

- 对外只暴露 **18735**（HTTPS）；后端 8080 绑 `127.0.0.1`，不对公网开放。
- 前端走同源反代，**无跨域**，后端无需 CORS 配置。
- 项目**无 Redis**（缓存是 JVM 内 ConcurrentHashMap），无需额外缓存组件。

---

## 一、本地构建（不在服务器编译）

### 1.1 后端 jar

```powershell
cd crossask-ai-backend
mvn -pl crossask-api -am clean package -DskipTests
# 产物：crossask-api/target/crossask-api-0.1.0-SNAPSHOT.jar
```

### 1.2 前端 dist

```powershell
cd crossask-ai-frontend
npm install
npm run build
# 产物：dist/
```

---

## 二、后端：构建并上传镜像

方式 A 与方式 B 二选一。

### 方式 A：本地构建镜像 → 导出 → 上传（无需镜像仓库，推荐）

```powershell
cd crossask-ai-backend
# 1. 构建镜像（Dockerfile 默认 COPY crossask-api/target 下的 jar）
docker build -t crossask-api:1.1 .
# 2. 导出为 tar
docker save crossask-api:1.1 -o crossask-api-1.1.tar
# 3. 上传到服务器
scp crossask-api-1.1.tar user@crossask.jxing.tech:/www/wwwroot/crossask-ai/crossask-ai-backend/
```

服务器上载入：
```bash
cd /www/wwwroot/crossask-ai/crossask-ai-backend
docker load -i crossask-api-1.1.tar
```

### 方式 B：用镜像仓库（有 Harbor/阿里云 ACR 时）

```powershell
docker build -t <registry>/crossask-api:1.1 .
docker push <registry>/crossask-api:1.1
# 服务器：docker pull <registry>/crossask-api:1.1
```

---

## 三、后端：服务器上运行容器（docker run）

### 3.1 准备环境变量文件

```bash
cd /www/wwwroot/crossask-ai/crossask-ai-backend
cp .env.example .env
vi .env   # 填入真实 DASHSCOPE_API_KEY 和 CROSSASK_AI_MYSQL_PASSWORD
```

### 3.2 载入镜像

```bash
cd /www/wwwroot/crossask-ai/crossask-ai-backend
docker load -i crossask-api-1.1.tar
```

### 3.3 启动容器

```bash
# 如果已有旧容器，先删除
docker rm -f crossask-api 2>/dev/null || true

# 只绑定 127.0.0.1，避免 8080 暴露到公网
docker run -d \
  --name crossask-api \
  --restart unless-stopped \
  --env-file /www/wwwroot/crossask-ai/crossask-ai-backend/.env \
  -p 127.0.0.1:8080:8080 \
  crossask-api:1.1
```

> 后端 8080 只在服务器内部可访问，外网只能通过 Nginx 的 18735 端口访问。

### 3.4 验证后端

```bash
docker ps --filter name=crossask-api
docker logs -f crossask-api          # 看到 "Started CrossAskApplication" 即成功
curl http://127.0.0.1:8080/sessions  # 应返回 JSON（可能是 []）
```

---

## 四、前端 + Nginx

### 4.1 上传 dist

```powershell
scp -r crossask-ai-frontend/dist/* user@crossask.jxing.tech:/www/wwwroot/crossask-ai/crossask-ai-frontend/dist/
```

### 4.2 配置 Nginx

Nginx 配置文件也放在项目目录内：

```text
/www/wwwroot/crossask-ai/crossask-ai-frontend/crossask.conf
```

推荐用软链接挂到 Nginx 配置目录，后续改项目内配置即可：

```bash
sudo ln -sf /www/wwwroot/crossask-ai/crossask-ai-frontend/crossask.conf /etc/nginx/conf.d/crossask.conf
sudo nginx -t
sudo systemctl reload nginx
```

### 4.3 申请 HTTPS 证书（Let's Encrypt）

```bash
sudo certbot certonly --nginx -d crossask.jxing.tech
# 证书生成在 /etc/letsencrypt/live/crossask.jxing.tech/
```

### 4.4 重载 Nginx

```bash
sudo nginx -t              # 校验配置
sudo systemctl reload nginx
```

---

## 五、防火墙 / 安全组

放行对外端口，**不要**放行 8080：

```bash
# 服务器防火墙
sudo firewall-cmd --permanent --add-port=18735/tcp
sudo firewall-cmd --permanent --add-port=80/tcp     # 用于 HTTP→HTTPS 跳转 + certbot
sudo firewall-cmd --reload
```

> 云服务器还需在**云控制台安全组**放行 18735 和 80。

---

## 六、验证上线

浏览器访问 **https://crossask.jxing.tech:18735**

- [ ] 页面正常加载（Claude 风格界面）
- [ ] 发一条消息，回答**逐字流式**出现（验证 SSE 反代生效）
- [ ] 商品类问题出现商品卡片、文档类出现来源
- [ ] 刷新页面对话保留、侧边栏历史会话正常

---

## 七、更新发布（后续迭代）

```bash
# 后端更新：本地构建新镜像并导出 crossask-api-1.2.tar，上传到 crossask-ai-backend/
cd /www/wwwroot/crossask-ai/crossask-ai-backend
docker load -i crossask-api-1.2.tar

docker rm -f crossask-api 2>/dev/null || true
docker run -d \
  --name crossask-api \
  --restart unless-stopped \
  --env-file /www/wwwroot/crossask-ai/crossask-ai-backend/.env \
  -p 127.0.0.1:8080:8080 \
  crossask-api:1.2

# 前端更新
npm run build
scp -r dist/* user@server:/www/wwwroot/crossask-ai/crossask-ai-frontend/dist/
# Nginx 无需重启，静态文件直接生效（带 hash，浏览器自动取新版）
```

## 八、回滚

```bash
# 后端：切回上一版镜像，例如 crossask-api:1.1
docker rm -f crossask-api 2>/dev/null || true
docker run -d \
  --name crossask-api \
  --restart unless-stopped \
  --env-file /www/wwwroot/crossask-ai/crossask-ai-backend/.env \
  -p 127.0.0.1:8080:8080 \
  crossask-api:1.1

# 前端：保留上一版 dist 备份目录，cp 回去即可
```

---

## 常见问题

| 现象 | 排查 |
|---|---|
| 流式回答卡住、一次性出现 | Nginx `/api` 块的 `proxy_buffering off` 是否生效；`nginx -t` 后 reload |
| 502 Bad Gateway | 容器是否在跑（`docker ps`）；8080 是否监听（`curl 127.0.0.1:8080/sessions`） |
| 启动报 DB 连接失败 | `.env` 的 MySQL 密码；服务器到 101.96.211.131:3306 是否通 |
| 403/额度错误 | `.env` 的 `DASHSCOPE_API_KEY` 是否有效、有额度 |
| 证书错误 | certbot 是否成功；证书路径与 conf 中是否一致 |
