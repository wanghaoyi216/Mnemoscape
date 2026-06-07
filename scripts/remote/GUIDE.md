# Mnemoscape 工位电脑部署 — 操作手册 (你来跑)

> 这里只剩两个事情你要做。前面那一摞 PowerShell 都不用管,直接看下面这份。

---

## 一、你的电脑上要做什么 (0 个步骤)

什么都不用做。`docker-compose.remote.yml` 和初始化文件早就上传到了 **工位电脑的 `D:\MnemoscapeInfra\`** 下面 (我用 scp 同步过去的),目录布局也已经建好了:

```
D:\MnemoscapeInfra\
├── docker-compose.remote.yml           ← 这次重新生成的最终版,直接用
├── docker-credential-noop.exe          ← SSH 场景下绕过 credential helper 的小工具
├── mysql\init\
│   ├── 01-schema-auth.sql              ← 第一次启动会自动建库建表
│   ├── 02-schema-memory.sql
│   └── 03-schema-resonance.sql
└── rabbitmq\
    └── definitions.json                ← MQ 队列/交换机预定义
```

镜像我已经全删了,你这边一干二净。

---

## 二、工位电脑上要做什么 (3 条命令)

RDP / 在工位电脑上打开 PowerShell (普通 PowerShell 就行,不需要管理员),然后:

```powershell
# 1. 进部署目录
cd D:\MnemoscapeInfra

# 2. 一键启动(全部 8 个中间件)
docker compose -f docker-compose.remote.yml up -d

# 3. 看一下状态,等所有服务 healthy
docker compose -f docker-compose.remote.yml ps
```

第一次跑大概要 **5-10 分钟**,主要是拉镜像 (~5 GB):

| 镜像 | 大小 |
|---|---|
| mysql:8.0 | ~1.1 GB |
| milvusdb/milvus:v2.3.21 | ~1.34 GB |
| nacos/nacos-server:v2.3.2 | ~1.27 GB |
| neo4j:5-community | ~1.03 GB |
| 其余 | ~0.5 GB |

镜像数据存在 Docker Desktop 的 WSL 虚拟盘里,你之前已经把它链接到 D 盘了,**绝对不会占 C 盘**。

> **如果 `docker compose up -d` 报 `error getting credentials - A specified logon session does not exist`**:
> 这是 Windows OpenSSH 启动 Docker 时的 known issue。本地 PowerShell 不会遇到 (因为本地有完整 logon token)。万一你是在 SSH 登录到自己的工位电脑跑的,把 `D:\MnemoscapeInfra\docker-credential-noop.exe` 复制到 `%USERPROFILE%\bin\` 下面,然后改 `~/.docker/config.json`:
> ```json
> { "auths": {}, "credsStore": "noop", "currentContext": "desktop-linux" }
> ```

---

## 三、网络拓扑 — 它们是怎么连起来的

所有服务都跑在 **同一个 Docker bridge 网络 `mnemoscape-infra-net`** 里,容器之间通过 **服务名 (DNS)** 互相寻址。同时每个容器把端口绑到 host 的 0.0.0.0,所以你的笔记本通过 Tailscale 直连 `100.66.166.46:<port>` 就能用。

```
                ┌─────────────────────────────────────────────────────┐
                │             mnemoscape-infra-net (bridge)            │
                │                                                      │
                │  ┌───────┐  ┌───────┐  ┌────────┐  ┌──────────┐    │
                │  │ mysql │  │ redis │  │ nacos  │  │ rabbitmq │    │
                │  └───┬───┘  └───┬───┘  └───┬────┘  └────┬─────┘    │
                │      │ 3306    │ 6379    │ 8848        │ 5672      │
                │      │         │         │ 9848        │ 15672     │
                │  ┌───┴───┐  ┌──┴────┐  ┌─┴──────┐  ┌──┴───────┐   │
                │  │ minio │  │ neo4j │  │  etcd  │  │  milvus  │   │
                │  └───┬───┘  └───┬───┘  └────────┘  └────┬─────┘    │
                │   9000/9001  7474/7687  (内部)       19530/9091     │
                └──────┼─────────┼───────────────────────┼────────────┘
                       │         │                       │
                       ▼         ▼                       ▼
              Host (Windows): 0.0.0.0:<port>  ← 你的笔记本通过 Tailscale 100.66.166.46 直连
```

容器之间互相找的时候 (比如 milvus 找 etcd / minio):用服务名 `etcd:2379`、`minio:9000` 就行。
笔记本上的 Spring Boot 找 MySQL/Redis:用 `100.66.166.46:3306`、`100.66.166.46:6379`。

---

## 四、笔记本本机怎么连上来

仓库里 `backend\.env.workpc` 我已经写好了所有连接参数。在本机启动后端之前:

```powershell
# 在仓库根目录 M:\Study\ProjectTest\Mnemoscape
. .\scripts\remote\Use-LocalDev-WorkpcInfra.ps1
```

这个脚本会把 `MYSQL_HOST=100.66.166.46`、`REDIS_HOST=...`、`NACOS_HOST=...` 全部写进当前 PowerShell 的环境变量。然后就可以:

```powershell
cd .\backend
.\mvnw -pl api-gateway       spring-boot:run
.\mvnw -pl auth-service      spring-boot:run    # 另一个窗口
.\mvnw -pl memory-service    spring-boot:run    # 另一个窗口
# ... 共 6 个服务
```

每个服务的 `application.yml` 早就改成 `${MYSQL_HOST:localhost}` 这种占位形式了,env 一加就指向工位电脑。

前端:
```powershell
cd .\frontend
npm install
npm run dev      # http://localhost:5173
```

前端的 `vite.config.ts` 把 `/api` 代理到 `http://localhost:8080`,就是你本机的 api-gateway。链路:
```
浏览器:5173 ─→ vite proxy ─→ 本机:8080 (api-gateway) ─→ 本机 8081-8085 (业务服务) ─→ 100.66.166.46:3306/6379/...
```

---

## 五、常用运维命令 (都在工位电脑上跑)

```powershell
cd D:\MnemoscapeInfra

# 看全部状态 / 端口 / 健康
docker compose -f docker-compose.remote.yml ps

# 跟随某个服务的日志
docker compose -f docker-compose.remote.yml logs -f nacos
docker compose -f docker-compose.remote.yml logs -f mysql

# 重启某个服务
docker compose -f docker-compose.remote.yml restart nacos

# 停掉全部 (保留数据)
docker compose -f docker-compose.remote.yml down

# 停掉并删除所有数据 ⚠️ 慎用
docker compose -f docker-compose.remote.yml down -v
Remove-Item -Recurse -Force D:\MnemoscapeInfra\mysql\data,
                            D:\MnemoscapeInfra\redis\data,
                            D:\MnemoscapeInfra\nacos\data,
                            D:\MnemoscapeInfra\rabbitmq\data,
                            D:\MnemoscapeInfra\minio\data,
                            D:\MnemoscapeInfra\neo4j\data,
                            D:\MnemoscapeInfra\etcd\data,
                            D:\MnemoscapeInfra\milvus\data
```

---

## 六、各服务访问地址速查

| 服务 | 协议端口 | 凭证 | UI / 用途 |
|---|---|---|---|
| MySQL | tcp 3306 | root / root123 | 业务库 mnemoscape_auth / _memory / _resonance |
| Redis | tcp 6379 | (无密) | 缓存、限流计数器 |
| Nacos | http 8848 | (匿名) | http://100.66.166.46:8848/nacos |
| Nacos gRPC | tcp 9848 | — | 客户端 SDK 推荐走 gRPC |
| RabbitMQ | tcp 5672 | guest / guest | AMQP |
| RabbitMQ UI | http 15672 | guest / guest | http://100.66.166.46:15672 |
| MinIO API | http 9000 | minioadmin / minioadmin123 | S3 兼容 |
| MinIO 控制台 | http 9001 | 同上 | http://100.66.166.46:9001 |
| Neo4j 浏览器 | http 7474 | neo4j / password123 | http://100.66.166.46:7474 |
| Neo4j Bolt | bolt 7687 | 同上 | 程序连接 |
| Milvus | grpc 19530 | (无密) | 向量库 |
| Milvus health | http 9091 | — | http://100.66.166.46:9091/healthz |

---

## 七、防火墙 (只在第一次配)

笔记本 → 工位电脑 通过 Tailscale 走,Tailscale 自己会建立直连,**Windows 默认防火墙不会阻挡** (Tailscale 装好时已自动放行)。所以默认情况下你**不用碰防火墙**。

但如果某个端口连不上,在工位电脑用 **管理员 PowerShell** 跑一遍下面这段就稳了:

```powershell
$ports = 3306,6379,8848,9848,5672,15672,9000,9001,7474,7687,19530,9091
foreach ($p in $ports) {
  $name = "Mnemoscape-$p"
  if (-not (Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName $name -Direction Inbound `
      -Protocol TCP -LocalPort $p -Action Allow -Profile Any | Out-Null
    Write-Host "allowed inbound tcp/$p"
  }
}
```

---

## 八、出问题了怎么排查

1. **某个容器一直 `restarting` / `unhealthy`** → `docker compose logs -f <service>` 看日志,通常是密码不对、初始化脚本报错、内存不足
2. **MySQL 初始化 SQL 没生效** → 它只在数据卷为空时跑一次。如果你想重新跑,把 `D:\MnemoscapeInfra\mysql\data\` 整个删了再 `up -d`
3. **本机 Spring Boot 连不上** → 用 `Test-NetConnection 100.66.166.46 -Port 3306` 看通不通;不通的话先去工位 RDP 上 `docker ps`,确认对应容器是 `healthy`
4. **Docker Desktop UI 里看不到这些镜像/容器** → 检查 Docker Desktop 右下角的 Engine 是不是 Linux,Context 是不是 `desktop-linux`;UI 偶尔需要手动刷新一下
5. **D 盘空间满了** → 大概率是 Docker WSL vhdx 涨上来。可以在 Docker Desktop → Settings → Resources → Advanced 调整 vhdx 大小上限,或在 PowerShell 里 `wsl --shutdown` 然后 `docker system prune -a -f` 清未用镜像

---

完事了。三句话总结你现在的下一步:

1. **登工位电脑 → `cd D:\MnemoscapeInfra` → `docker compose -f docker-compose.remote.yml up -d`**
2. **等 ps 全部 healthy**
3. **回本机,`.\scripts\remote\Use-LocalDev-WorkpcInfra.ps1`,然后 `mvnw spring-boot:run` 一个一个起服务**
