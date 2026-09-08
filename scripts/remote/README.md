# Mnemoscape 远程中间件 (workpc) 运维脚本

所有脚本设计为 **workpc 本地 PowerShell 7+** 执行（`本机访问无需密码`）。
不需要 SSH 跳板。

## 一键启动

```powershell
# 1. 把整个项目 git pull 到 workpc（或者 rsync 过来），例如 D:\Mnemoscape
cd D:\Mnemoscape\scripts\remote

# 2. 启动所有中间件容器 + 推 Nacos 配置
.\start-remote-infra.ps1

# 只看就绪状态（不重启容器）
.\start-remote-infra.ps1 -OnlyWait

# 跳过 docker up（容器已跑），只同步配置 + 推 Nacos
.\start-remote-infra.ps1 -SkipDockerUp

# 启动后不推 Nacos
.\start-remote-infra.ps1 -SkipNacosPush
```

## 其他脚本

| 脚本 | 用途 |
|---|---|
| `start-remote-infra.ps1` | docker compose up + 等待就绪 + 推 Nacos 配置 |
| `stop-remote-infra.ps1` | `docker compose down`（保留数据卷） |
| `status-remote-infra.ps1` | 端口连通性 + 容器列表 |
| `diagnose-nacos.ps1` | 排 Nacos 9848 timeout 专项 |
| `rabbitmq/definitions.json` | 5 队列 + DLX + policy 定义 |
| `rabbitmq/rabbitmq.conf` | RabbitMQ 主配置（开启 mgmt, load_definitions） |
| `nacos/ai-service.yaml` | AI 业务配置（推 Nacos 用） |
| `nacos/ai-service-dev.yaml` | dev profile 覆盖 |
| `nacos/ai-service-prod.yaml` | prod profile 覆盖 |
| `nacos/push-nacos-config.ps1` | 推送 yaml 到 Nacos OpenAPI |
| `nacos/push-nacos-config.sh` | 推送 yaml 到 Nacos OpenAPI（Bash 备用） |

## 端口速查

| 服务 | Tailscale 端口 | 用户 / 密码 |
|---|---|---|
| MySQL | 100.66.166.46:3306 | root / root123 |
| Redis | 100.66.166.46:6379 | 无 |
| Nacos | 100.66.166.46:8848 (HTTP) + 9848 (gRPC) | 关闭鉴权 |
| RabbitMQ | 100.66.166.46:5672 (AMQP) + 15672 (UI) | guest / guest |
| MinIO | 100.66.166.46:9000 (S3) + 9001 (UI) | minioadmin / minioadmin123 |
| Neo4j | 100.66.166.46:7687 (Bolt) + 7474 (UI) | neo4j / password123 |
| Milvus | 100.66.166.46:19530 (gRPC) + 9091 (UI) | 无 |

## 数据卷位置

所有数据卷挂载到 `D:\MnemoscapeInfra\<service>\`，**不会写 C 盘**：
```
D:\MnemoscapeInfra\mysql\data
D:\MnemoscapeInfra\redis\data
D:\MnemoscapeInfra\nacos\data
D:\MnemoscapeInfra\rabbitmq\data
D:\MnemoscapeInfra\minio\data
D:\MnemoscapeInfra\neo4j\data
D:\MnemoscapeInfra\milvus\data
D:\MnemoscapeInfra\etcd\data
```
