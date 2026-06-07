# Mnemoscape 后端优化任务 —— AI 接手包

## 一、项目背景

**Mnemoscape**：AI 驱动的个人记忆博物馆。6 个 Spring Boot 3.2.5 微服务（JDK 17），Alibaba Nacos 注册中心，Spring Cloud OpenFeign，MySQL + Redis + RabbitMQ + Neo4j + Milvus。

**工作目录**：`M:\Study\ProjectTest\Mnemoscape\backend\`
**前端**：`M:\Study\ProjectTest\Mnemoscape\frontend\` (Vue 3 + TS + Vite)
**环境文件**：`backend\.env.workpc`（Tailscale `100.66.166.46` 连远端 workpc 中间件）

### 6 服务端口
- 8080 api-gateway (Netty) — Spring Cloud Gateway
- 8081 auth-service — JWT、用户、好友、admin 角色
- 8082 memory-service — 记忆 CRUD、Neo4j 图谱、Milvus 向量
- 8083 ai-service — LLM、实体提取、向量 upsert
- 8084 resonance-service — 漂流瓶、聊天、achievement
- 8085 asset-service — 资源上传、MinIO、WebSocket

### 启动方式（已验证可行）
- `backend\Restart-MnemoscapeServices.ps1` 一键杀旧 + 启新（后台 java -jar）
- IDE Maven 启动 spring-boot:run（**用户主要用这个**）

---

## 二、已完成的优化（源码层面，需 rebuild）

### 1. RateLimit 完整实现（common 模块）
**目的**：流量消峰、防爆破、防 admin 端点过载

**文件**：
- `common/src/main/java/com/mnemoscape/common/ratelimit/RateLimit.java` —— 注解（key/limit/windowSeconds/dimension/message）
- `RateLimitExceededException.java` —— 继承 `BizException(code=429)`
- `RateLimiter.java` —— 接口 + `Decision` record
- `RedisSlidingWindowRateLimiter.java` —— Lua 脚本 + ZSET 滑动窗口 + Fail-open
- `RateLimitAspect.java` —— `@Around` + 反射查注解（**关键修复**）
- `RateLimitAutoConfiguration.java` —— `@AutoConfiguration` + Bean
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` —— 显式注册
- `common/pom.xml` —— 加 `spring-boot-starter-aop`

**应用到**：
- `auth-service/.../AuthController.java`：login (10/min/IP) + register (5/min/IP)
- `memory-service/.../admin/AdminStatsController.java`：6 端点（30/30/30/20/20/10 per min）
- `resonance-service/.../admin/AdminResonanceController.java`：2 端点 (30/min)

**GlobalExceptionHandler** 加 `handleRateLimit` → 429 + `Retry-After` + `X-RateLimit-Bucket` 头
位置：`common/.../exception/GlobalExceptionHandler.java`

**已修复的关键 Bug**（**重要**）：原 `RateLimitAspect.around` 用 `@annotation(rateLimit) || @within(rateLimit)` pointcut + 形参 `RateLimit rateLimit`，但 Spring AOP 在 disjunction 时**未匹配侧的参数为 null** → `cfg.dimension()` NPE → login 500。**改用 `method.getAnnotation()` 反射查**，不依赖参数绑定。

### 2. N+1 查询修复
- `resonance-service/.../controller/ChatController.java::getMyGroups` —— `for findById` 循环 → `findAllById(IN)`
- `auth-service/.../controller/FriendController.java::listFriends` —— 同上

### 3. Outbox 模式（memory-service）
**目的**：MQ 失败时持久化事件，30s worker 重发，保证最终一致性

**文件**：
- `memory-service/.../messaging/outbox/OutboxEvent.java` —— JPA 实体，ddl-auto 自动建表
- `OutboxEventRepository.java` —— `findReady(PENDING, now, Pageable)` 批 50
- `OutboxEventPublisher.java` —— `stage()` (MANDATORY 事务) + `stageBestEffort()` (REQUIRES_NEW)
- `OutboxRetryScheduler.java` —— `@Scheduled(fixedDelay=30s)` 扫描 + 重发 + 指数退避 5s→320s
- `EventPublisher.java` —— `safePublish` 失败时调用 `outboxPublisher.stageBestEffort()` 兜底

**启用验证**：日志 `[outbox] worker started pending=0 failed=0`

### 4. Circuit Breaker（memory-service → ai-service）
**目的**：防 ai-service 慢响应拖死 memory 的 @Async 线程池

**文件**：
- `memory-service/.../client/AiServiceGateway.java` —— 4 方法全 `@CircuitBreaker(name="ai-service", fallbackMethod="...")` + `@Retry`
- `memory-service/pom.xml` —— 加 `resilience4j-spring-boot3`
- `memory-service/src/main/resources/application.yml` —— 加 resilience4j 配置
- `MemoryService.java` —— 3 处 Feign 调用改用 gateway（`syncIndexFallback` / `extractAndProjectGraph` / `enrichWithReconstruction`）

**配置**：
```yaml
slidingWindowSize: 10, minimumNumberOfCalls: 5, failureRateThreshold: 50
waitDurationInOpenState: 30s, permittedNumberOfCallsInHalfOpenState: 3
recordExceptions: IOException, TimeoutException, FeignException.ServiceUnavailable
retry: maxAttempts=3, waitDuration=200ms, exponentialBackoffMultiplier=2
```

### 5. GZip 响应压缩（common 模块）
- `common/.../config/GzipResponseConfig.java` —— `WebServerFactoryCustomizer` 设置 1KB+ JSON/HTML/XML 压缩
- @Configuration，common scanBasePackages 自动加载（**注意：api-gateway 用 Netty 不生效**）

### 6. Idempotency-Key 写路径幂等
- `common/.../idempotency/IdempotencyGuard.java` —— Redis SETNX + 24h TTL + Fail-open
- `memory-service/.../controller/MemoryController.java::create` —— 接收 `Idempotency-Key` header，命中缓存返回历史响应

### 7. 前端优化
- `frontend/src/views/admin/SystemHealthView.vue:134` —— 4s → 30s 轮询 + 错误指数退避 + visibilitychange
- `frontend/src/composables/useReconnectingWebSocket.ts` —— 新建 composable（1s→30s + ±20% jitter + visibility）
- `frontend/src/views/ChatView.vue` —— 替换为 `useReconnectingWebSocket`

### 8. 其他
- `ai-service/src/main/resources/application.yml` —— Neo4j URI 修复（bolt://100.66.166.46:7687）

---

## 三、当前真实状态（**接手时务必先核**）

### 服务运行状态
**注意：以下 PID 是用户 8:41 启的旧 jar（没含 RateLimit 修复 / 没含 IdempotencyGuard / 仍带 Aspect NPE bug）**：
- 8080 api-gateway (PID 25656) — 旧 jar
- 8081 auth (PID 12368) — 旧 jar，**login 仍 500**（Aspect NPE 缺陷）
- 8082 memory (PID 31688) — **AI 启的旧 jar（21:00:55 build）**，outbox worker 已激活
- 8083 ai (PID 17092) — 旧 jar
- 8084 resonance (PID 26512) — 旧 jar
- 8085 asset (PID 11172) — 旧 jar

### 关键 jar 时间戳
- `common/target/common-1.0.0-SNAPSHOT.jar` —— 需看时间（**AI 修 Aspect 后未 rebuild**）
- `auth-service/target/auth-service-1.0.0-SNAPSHOT.jar` —— 20:13:54（**无** Aspect 修复 + 无 IdempotencyGuard）
- `memory-service/target/memory-service-1.0.0-SNAPSHOT.jar` —— 21:00:55（**有** Outbox + GZip + gateway，**无** Aspect 修复 + 无 IdempotencyGuard）

### 用户 rebuild 失败证据
- `mvn install` 在 `api-gateway` clean 阶段挂掉 —— 8080 进程锁 jar
- IDEA `spring-boot:run` 6 service 全 22:18 失败 "Application run failed" —— **Caused by 没贴出来**

---

## 四、接手后立即要做的（**优先级排序**）

### 1. 杀掉所有运行中的 6 service（释放 jar 锁）
```powershell
# 1a. 一次性杀 6 端口
8080,8081,8082,8083,8084,8085 | ForEach-Object {
    $c = Get-NetTCPConnection -LocalPort $_ -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { Stop-Process -Id $c.OwningProcess -Force }
}
```

### 2. 抓 22:18 "Application run failed" 真错
```powershell
# 用户之前的失败原因未知。直接后台跑看完整堆栈：
$envFile = "M:\Study\ProjectTest\Mnemoscape\backend\.env.workpc"
Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1].Trim(), $Matches[2].Trim(), 'Process')
    }
}
$logPath = "M:\Study\ProjectTest\Mnemoscape\backend\logs\memory-startup.log"
$proc = Start-Process -FilePath "java" -ArgumentList @(
    "-jar", "M:\Study\ProjectTest\Mnemoscape\backend\memory-service\target\memory-service-1.0.0-SNAPSHOT.jar",
    "--server.port=8082"
) -WorkingDirectory "M:\Study\ProjectTest\Mnemoscape\backend\memory-service" `
  -RedirectStandardOutput $logPath -RedirectStandardError "$logPath.err" -WindowStyle Hidden -PassThru
Start-Sleep 30
Get-Content $logPath -Tail 100
Get-Content "$logPath.err" -Tail 100
```

### 3. 验证 Aspect 修复 + rebuild
**确认 `common/src/main/java/com/mnemoscape/common/ratelimit/RateLimitAspect.java` 已修**：
```java
@Around("@annotation(rateLimit) || @within(rateLimit)")
public Object around(ProceedingJoinPoint pjp) throws Throwable {
    RateLimit cfg = resolveConfig(pjp);  // 反射查，不依赖参数绑定
    if (cfg == null) return pjp.proceed();
    ...
}
private RateLimit resolveConfig(ProceedingJoinPoint pjp) {
    MethodSignature sig = (MethodSignature) pjp.getSignature();
    Method method = sig.getMethod();
    RateLimit onMethod = method.getAnnotation(RateLimit.class);
    if (onMethod != null) return onMethod;
    ...
}
```

### 4. Rebuild
```powershell
$env:MAVEN_OPTS = "-Xmx4g -Xms512m"
cd M:\Study\ProjectTest\Mnemoscape\backend
.\mvnw.cmd clean install -DskipTests -pl common,auth-service,memory-service,resonance-service -am
```

### 5. 启 6 service（用 `Restart-MnemoscapeServices.ps1` 或 IDE）
脚本会：① 杀占端口进程 ② java -jar 启 6 个 ③ 等端口 UP

### 6. 测试用例（**做完上一步后跑这些**）
1. **RateLimit 429**：35×`GET /api/v1/admin/stats/active-user-counts` 期望 30 OK + 5×429
2. **login 不再 500**：`POST /api/v1/auth/login` 用正确密码应返 token
3. **Outbox 表存在**：MySQL `SHOW TABLES LIKE 'outbox%'`  应有 `outbox_event`
4. **GZip**：`curl -H "Accept-Encoding: gzip" .../admin/stats/memory-trends` 响应头应有 `Content-Encoding: gzip`
5. **N+1**：`GET /api/v1/chat/groups` （带 JWT） 打开 SQL log 应只 1 条 IN query
6. **Idempotency-Key**：连发 2 次同 key `POST /api/v1/memories` 只建 1 条

---

## 五、待办（已完成外剩余项）

- [ ] **MQ 消费幂等**（ai-service + resonance-service 端）—— `ConsumedEvent` 表 + eventId 唯一约束 OR Redis SETNX dedup
- [ ] **HikariCP 调优**（6 service）—— `connection-timeout` / `leak-detection-threshold` / `max-lifetime` 统一
- [ ] **前端 useWebSocket.ts 升级**（用新 `useReconnectingWebSocket` 替换原裸 `new WebSocket`）
- [ ] **性能基线文档** —— `docs/perf-baseline.md`，记录 6 service P50/P99 延迟 + 限流配置 + cache hit rate
- [ ] **api-gateway GZip** —— 改用 Netty 的 `HttpServerCodec` compression 配置（不是 servlet customizer）

---

## 六、易踩坑提醒

1. **Maven heap 至少 4GB**（`MAVEN_OPTS=-Xmx4g`），否则 memory-service 编译 OOM
2. **Windows jar 文件锁**：java -jar 启动后 jar 被锁，repackage 时 mvn 报 "Unable to rename" → **先 Stop-Process 再 mvn**
3. **Spring AOP disjunction 参数绑定**：`@annotation(x) || @within(x)` 时 x 形参为 null，**必须用反射查注解**
4. **api-gateway 用 Netty**，不带 servlet；`WebServerFactoryCustomizer<ConfigurableServletWebServerFactory>` 不生效
5. **memory-service 用 `ddl-auto: update`**，新 JPA 实体自动建表（OutboxEvent 已被 ddl-auto 创建）
6. **jar 进程在 `java -jar` 模式下占目录 + jar 文件锁**；用 `Start-Process -WindowStyle Hidden` 后台启，但 IDE spring-boot:run 不会锁 jar
7. **Spring Cloud LoadBalancer 默认缓存**会有 WARN（`n$LoadBalancerCaffeineWarnLogger`）—— 已知，不影响运行
8. **.env.workpc 必须在启 java 之前 source**（`EnvLoader.load()` 在每个 `*Application.main()` 第一行调用）

---

## 七、关键代码片段速查

### RateLimitAspect 修复版（确认是这一版）
文件：`common/src/main/java/com/mnemoscape/common/ratelimit/RateLimitAspect.java`
- `around(ProceedingJoinPoint pjp)` —— 不带 RateLimit 参数
- `resolveConfig(pjp)` —— 反射 `method.getAnnotation()` → `declaringClass.getAnnotation()` → enclosing class

### Outbox 自动建表
文件：`memory-service/.../messaging/outbox/OutboxEvent.java`
- `@Entity @Table(name = "outbox_event", indexes = { @Index(name = "idx_outbox_status_next", columnList = "status,next_attempt_at"), @Index(name = "uk_outbox_event_id", columnList = "event_id", unique = true) })`

### CircuitBreaker 接入点
文件：`memory-service/.../client/AiServiceGateway.java`
- `@Component public class AiServiceGateway`
- 4 方法：`extractEntities` / `reconstruct` / `indexVector` / `deleteVector`
- 每个有 `fallbackMethod = "xxxFallback"`（同签名 + Throwable 尾参）

---

## 八、接手后第一句话建议

"我接手 Mnemoscape 后端优化任务。当前 6 service 旧 jar 在跑（8:41 PID 25656/12368/17092/26512/11172 + AI 启的 31688）。第一件事是：① 杀 6 端口进程释放 jar 锁 ② 抓用户 22:18 'Application run failed' 的真错（Caused by 链） ③ 验证 RateLimitAspect 修复已写盘 ④ rebuild common+4 service ⑤ 跑测试用例 6 项。"
