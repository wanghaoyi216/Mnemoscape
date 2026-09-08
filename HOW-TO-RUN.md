# Mnemoscape 怎么跑起来

不到一页纸的最小操作清单。所有命令都在仓库根目录执行。

## 前置

| 工具 | 版本 | 说明 |
|---|---|---|
| Docker Desktop | 4.20+ | 含 docker compose v2 |
| JDK | 17 或 25 | 已验证 25.0.1 可跑 |
| Node | 18.18+ | 已验证 npm + Vite 5 |
| Python | 3.10+ | 可选，仅压图脚本用 |

不需要本机装 Maven / Neo4j / MySQL / RabbitMQ — Docker 全包。

## 一键启动（推荐）

```bash
./scripts/start-services.sh
```

它做三件事：
1. `backend/` 下跑 `./mvnw clean package -DskipTests` 产出每个微服务的 fat jar
2. `docker compose -f deploy/docker-compose.all.yml up -d --build` 起所有容器
3. 等 30 秒后 curl 每个端口的 `/actuator/health`，输出健康状态

如果你是手动起单个后端服务，优先用 `scripts/Start-LocalDevServices.ps1`。它会自动加载 `backend/.env.workpc`，并且现在会带 `-am` 重新构建依赖模块，避免 `common` 还停留在 `~/.m2` 里的旧 SNAPSHOT。

完成后访问：

- 前端：http://localhost:5173
- 网关：http://localhost:8080
- Swagger（各服务）：http://localhost:{8081..8085}/swagger-ui.html
- Nacos 控制台：http://localhost:8848/nacos （账密 nacos / nacos）
- RabbitMQ 管理：http://localhost:15672 （guest / guest）
- MinIO 控制台：http://localhost:9001 （minioadmin / minioadmin123）
- Neo4j 浏览器：http://localhost:7474 （neo4j / password123）

## 分步启动（出问题时排查用）

### 1) 起基础设施
```bash
docker compose -f deploy/docker-compose.yml up -d
```
等 ~30s。可通过 `docker compose ps` 看 nacos/mysql/rabbitmq/neo4j/minio 是否 healthy。

### 2) 编译后端
```bash
cd backend
./mvnw clean package -DskipTests
```
应看到 `BUILD SUCCESS`，每个 service 目录下生成 `target/*.jar`。

### 3) 起微服务 + 前端
```bash
docker compose -f deploy/docker-compose.services.yml up -d --build
```

## 仅前端开发模式

```bash
cd frontend
npm install   # 首次
npm run dev   # 起 Vite dev server，默认 5173
```
前端的 `/api` 请求会自动代理到 `localhost:8080`（网关）。所以仍需后端在跑。

## 停止 & 清理

```bash
./scripts/stop-all.sh                          # 停所有容器
docker compose -f deploy/docker-compose.all.yml down -v   # 连卷一起删（数据库会被清）
```

> 只重启前端 / 后端不会清空记忆数据；数据写在工位机 MySQL 的持久化卷里。只有 `down -v` 或手动删卷才会把历史数据清掉。

## 验证 / 冒烟测试

```bash
./scripts/test-e2e.sh         # 端到端 API 测试（注册/登录/记忆/共鸣链路）
./scripts/verify-trace.sh     # 验证跨服务 traceId 透传（需先 up）
```

## 已知重点

1. **首次启动慢**：Nacos / Neo4j 健康检查 30~45s，前端会先报 502，等齐就好。
2. **PowerShell 终端**：用 `mvnw.cmd`；Git Bash / WSL 用 `mvnw`（已修好 `-Dmaven.multiModuleProjectDirectory`）。
3. **Neo4j 不可达不会让记忆服务挂**：`MemoryGraphService` 是 best-effort，写不进去只打 WARN。
4. **WebP 资产**：`frontend/public/media/` 里只剩 WebP（PNG 已删）。要重新压缩用：
   ```bash
   python scripts/compress-media.py
   ```

## 端口表

| 端口 | 服务 |
|---|---|
| 5173 | 前端（Vite dev 或 nginx 容器） |
| 8080 | api-gateway |
| 8081 | auth-service |
| 8082 | memory-service |
| 8083 | ai-service |
| 8084 | resonance-service（WebSocket 也在这） |
| 8085 | asset-service |
| 3306 | MySQL |
| 5672 / 15672 | RabbitMQ broker / 管理界面 |
| 6379 | Redis |
| 7474 / 7687 | Neo4j HTTP / Bolt |
| 8848 | Nacos |
| 9000 / 9001 | MinIO API / 控制台 |

## 出错速查

| 症状 | 多半原因 | 解决 |
|---|---|---|
| 网关 401 | JWT 失效或没登录 | 重新走 `/api/v1/auth/login` 拿 token |
| 记忆详情 500 | 老版本未修复的 Cache 异常 | 已修，最新代码 → 404 with requestId |
| docker build 报 `target/*.jar not found` | 没先跑 `./mvnw package` | 用 `start-services.sh` 或先单独编译 |
| `mvn` 报 multiModuleProjectDirectory | 用了旧版 `mvnw` | 用本仓库最新 `backend/mvnw` |
| 前端访问图片 404 | dist 里没 WebP | 检查 `frontend/public/media/images/` 是否有 .webp |
| 500 还在显示旧的“坍塌”文案 | 本地服务吃到了旧的 `common` SNAPSHOT | 跑 `scripts/Diagnose-LocalDev.ps1`，再用 `Start-LocalDevServices.ps1` 重启 |

## 重要功能位置

| 功能 | 入口 |
|---|---|
| 记忆列表 | `/memories` |
| 新建记忆 | `/memories/new` （提交时有沙漏视频弹层） |
| 记忆详情 | `/memories/:id` |
| 3D 场景漫游 | `/scene/:id` |
| **记忆星图（3D 星空）** | `/memories/graph` |
| **时光长河** | `/memories/timeline` |
| 个人主页 + 情绪吸引子 | `/profile` |
| 共鸣大厅 | `/resonance` |
| 共鸣空间（3D 留言信标，按 E 查看） | `/resonance/:id` |
