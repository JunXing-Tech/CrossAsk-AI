# db-init

CrossAsk 后端数据库初始化 SQL 脚本目录。

## 使用方式

按版本号顺序，用具备 CREATE 权限的账号（通常是 `root` 或 DBA 账号）在 MySQL 客户端执行：

```bash
mysql -h 101.96.211.131 -u root -p < V0_7__products.sql
```

或在 Navicat / DBeaver / phpMyAdmin 里复制粘贴执行。

## 脚本列表

| 文件 | 引入版本 | 说明 |
|---|---|---|
| `V0_7__products.sql` | v0.7 | 建库 `crossask` + 建商品表 `products`（含全文索引） |

## 注意事项

- 业务账号（`crossask_ai`）需对 `crossask.*` 拥有 `ALL PRIVILEGES`：
  ```sql
  GRANT ALL PRIVILEGES ON crossask.* TO 'crossask_ai'@'%';
  FLUSH PRIVILEGES;
  ```
- Spring Boot 启动时不再自动建库建表（v0.7 起取消了 `spring.sql.init`），完全由本目录脚本掌控。
