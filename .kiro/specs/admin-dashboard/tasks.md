# Implementation Plan: 管理端大屏可视化看板 (admin-dashboard)

## Overview

按 design.md 的部署顺序拆解：先在 `backend/common` 中铺设共享工具（JWT role 解析、时间桶、ISO 日期、QueryHasher、BypassCache、AdminAuditLogger、AdminMetrics），随后在 `auth-service` 完成 `users.role` 数据模型与 JWT claim 颁发，再在 `api-gateway` 加入 `X-User-Role` 注入与 `AdminGuardFilter`/`/admin/health`，并在所有后端服务的 `SecurityConfig` 加 `/api/v1/admin/**` 规则。这之后并行推进 auth/memory/resonance 三个服务的聚合端点实现，最后落到 Vue 3 前端的路由守卫、`<AdminPanel>` 包装、i18n 字典与 8 个面板视图。属性测试（jqwik / fast-check）紧跟实现，覆盖 design.md `## Correctness Properties` 中的 12 条性质。

后端使用 Java 17 + Spring Boot（沿用仓库现有技术栈），前端使用 TypeScript + Vue 3 + ECharts + maplibre-gl/deck.gl。

## Tasks

- [x] 1. 在 `backend/common` 中铺设共享基础设施
  - [x] 1.1 扩展 `common/.../security/JwtAuthFilter` 解析 `role` claim 并授予 `ROLE_ADMIN` / `ROLE_USER`
    - 在既有 `doFilterInternal` 内 `claims.get("username", String.class)` 之后读取 `role` claim
    - 严格 case-sensitive 校验值集合 `{"ADMIN","USER"}`，缺失或非法值降级为 `USER` 并 WARN 日志（含 `sanitize(...)` 截断）
    - 用 `SimpleGrantedAuthority("ROLE_" + resolvedRole)` 构造 `UsernamePasswordAuthenticationToken`
    - _Requirements: 2.3, 2.4_
  
  - [x]* 1.2 为 `JwtAuthFilter` 编写 jqwik 属性测试
    - **Property 12: Authority mapping 全单射**
    - **Validates: Requirements 2.3, 2.4**
  
  - [x] 1.3 新建 `common/.../admin/codec/TimeDimensionCodec`（含 `Dimension` 枚举 + `tryParse` + `print`）
    - 严格大写 case-sensitive 解析；不接受 alias / 小写
    - `print` 输出 `name()` canonical 形式
    - _Requirements: 17.1, 17.2_
  
  - [x]* 1.4 为 `TimeDimensionCodec` 编写 jqwik round-trip 属性测试
    - **Property 1: TimeDimension round-trip**
    - **Validates: Requirements 17.1, 17.2**
  
  - [x] 1.5 新建 `common/.../admin/codec/IsoDateCodec`（包装 `DateTimeFormatter.ISO_LOCAL_DATE`）
    - `print(LocalDate)` 输出 `YYYY-MM-DD`；`tryParse(String)` 返回 `Optional<LocalDate>`
    - 解析失败返回 `Optional.empty()` 而非抛异常
    - _Requirements: 17.3, 17.4_
  
  - [x]* 1.6 为 `IsoDateCodec` 编写 jqwik round-trip 属性测试
    - **Property 2: ISO LocalDate round-trip**
    - **Validates: Requirements 17.3, 17.4**
  
  - [x] 1.7 新建 `common/.../admin/QueryHasher` 工具类
    - 用 `ObjectMapper` + `ORDER_MAP_ENTRIES_BY_KEYS` 做 canonical JSON
    - SHA-256 后取前 16 hex；任何异常 fallback 到 `"unhashable"`
    - _Requirements: 15.5_
  
  - [x]* 1.8 为 `QueryHasher` 编写单元测试
    - 测试同输入两次 hash 结果相同；输入 keys 顺序变化结果不变
    - 测试空 / null Map 行为；测试异常路径返回 `"unhashable"`
    - _Requirements: 15.5_
  
  - [x] 1.9 新建 `common/.../admin/cache/BypassOnFailureCacheManager` + `BypassCache` 装饰器
    - `getCache` 返回 `BypassCache`，`get` / `put` / `evict` / `clear` 捕获 `RedisConnectionFailureException` / `RedisSystemException` 并返回 null/静默失败 + WARN 日志
    - 注册 `@Bean @Primary CacheManager`，原始 `RedisCacheManager` 作为 delegate
    - _Requirements: 14.3_
  
  - [x]* 1.10 为 `BypassCache` 编写单元测试
    - mock `RedisCache` 抛 `RedisConnectionFailureException` → 验证 `get` 返回 null + WARN 日志
    - mock `put` 抛异常 → 验证静默吞噬不影响调用方
    - _Requirements: 14.3_
  
  - [x] 1.11 新建 `common/.../admin/AdminAuditLogger`（专属 logger `admin-audit` + structured kv）
    - 使用 `LoggerFactory.getLogger("admin-audit")` 与 `net.logstash.logback:logstash-logback-encoder` 的 `StructuredArguments`
    - 暴露 `logAccess(adminUserId, endpoint, queryHash, status, latencyMs)`
    - 在 `backend/common` `pom.xml` 加 `logstash-logback-encoder:7.4` 与 `spring-boot-starter-cache` 依赖
    - _Requirements: 15.4_
  
  - [x] 1.12 新建 `common/.../admin/metrics/AdminMetrics` Micrometer 装饰器
    - 暴露 `cacheHits` / `cacheMisses` / `uncachedLatency` / `degradedResponses` / `authzRejects` / `bootstrapAttempts` 计数器与计时器
    - 所有 metric 名以 `mnemoscape.admin.*` 为前缀，按设计文档 §Observability 表格打 tag
    - _Requirements: 14.5, 18.3, 3.5_

- [x] 2. 在 `auth-service` 落地 `users.role` 数据模型与 JWT 颁发
  - [x] 2.1 新建 SQL migration 脚本 `backend/auth-service/src/main/resources/db/admin-role.sql`
    - 内容：`ALTER TABLE users ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER' AFTER background_image_url;`
    - 兜底回填 + `idx_users_role` 索引 + `chk_users_role CHECK (role IN ('USER','ADMIN'))`
    - 在脚本顶部注释说明运维需在部署前手工执行
    - _Requirements: 1.1_
  
  - [x] 2.2 在 `User` JPA 实体新增 `role` 字段 + getter/setter + `@PrePersist` 兜底
    - `@Column(nullable = false, length = 16) private String role = "USER";`
    - `getRole()` 在 null 时返回 `"USER"`；`setRole` 对 null/blank 兜底为 `"USER"`，否则 `trim().toUpperCase()`
    - `onCreate()` 末尾 `if (role == null || role.isBlank()) role = "USER";`
    - _Requirements: 1.1, 1.2, 1.3_
  
  - [x]* 2.3 为 `User.role` 兜底逻辑编写单元测试
    - `setRole(null)` / `setRole("  ")` / `setRole("admin")` 后 `getRole()` 验证
    - `@PrePersist` 模拟测试
    - _Requirements: 1.2, 1.3_
  
  - [x] 2.4 修改 `JwtTokenProvider` 增加携带 `role` claim 的 `buildToken`
    - 新增 `generateAccessToken(String userId, String username, String role)` 重载
    - 保留旧签名 `(userId, username)` 内部转发为 `role="USER"`（向后兼容）
    - 在 `Jwts.builder()` 中 `.claim("role", "ADMIN".equals(role) ? "ADMIN" : "USER")`
    - 不修改 `sub` / `username` / `jti` / `exp` 既有语义
    - _Requirements: 2.1, 2.5_
  
  - [x]* 2.5 为 `JwtTokenProvider` 新签名编写单元测试
    - 解析回来后 `claims.get("role")` 等于输入；不传 role 时为 `"USER"`
    - 校验 `sub` / `username` / `jti` / `exp` 未被破坏
    - _Requirements: 2.1, 2.5_
  
  - [x] 2.6 修改 `AuthService.login` / `refresh` 在颁发 access token 时传入 `user.getRole()`
    - 同时把 `role` 写入 `AuthResponse` DTO（新增字段）
    - 在 `UserProfileResponse` DTO 加 `role` 字段，供前端 `fetchProfile` 写入 store
    - _Requirements: 2.1_
  
  - [x]* 2.7 为 `AuthService.login` 编写集成单测
    - 用 `role="ADMIN"` 的用户登录 → 返回 `AuthResponse.role == "ADMIN"`，token 解码后 claim 一致
    - _Requirements: 2.1_
  
  - [x] 2.8 在 `backend/auth-service/src/main/resources/logback-spring.xml` 增加 `admin-audit` 与 `admin-degradation` / `admin-authz` appender
    - `RollingFileAppender` 输出 `logs/admin-audit.log`，使用 `LogstashEncoder`
    - 三个 logger `additivity=false`，单独路由到对应文件
    - 在 `auth-service` / `memory-service` / `resonance-service` / `api-gateway` 同步该配置
    - _Requirements: 15.4, 18.3, 3.5_

- [x] 3. 在 `api-gateway` 注入 `X-User-Role` 头并实现 `AdminGuardFilter` 与 `/admin/health`
  - [x] 3.1 修改 `AuthGlobalFilter` 在 mutate request 时追加 `X-User-Role` 头
    - 读取 JWT `role` claim，严格映射 `{"ADMIN" → "ADMIN", 其它 → "USER"}`
    - 校验失败时不注入头（沿用既有 401 路径，R2.6）
    - _Requirements: 2.2, 2.6_
  
  - [x] 3.2 新建 `api-gateway/.../filter/AdminGuardFilter` 作为 reactive `WebFilter` order=-90
    - 仅对 `path.startsWith("/api/v1/admin/")` 生效
    - `X-User-Role` 缺失 → 401 `AUTH_REQUIRED`；非 `"ADMIN"` → 403 `ADMIN_REQUIRED`；通过则放行
    - 响应 body 用 `ApiResponse` JSON 形态
    - 每次决策（ALLOW / FORBIDDEN / UNAUTHORIZED）写 `admin-authz` 结构化日志（含 userId / userRole / path / method / decision / requestId）
    - 同时增加 `mnemoscape.admin.authz.rejects` 计数器
    - _Requirements: 3.1, 3.3, 3.4, 3.5_
  
  - [x]* 3.3 为 `AdminGuardFilter` 编写 WebFlux 测试
    - 无 `X-User-Role` → 401 + `AUTH_REQUIRED`
    - `X-User-Role=USER` → 403 + `ADMIN_REQUIRED`
    - `X-User-Role=ADMIN` → 放行下游
    - 验证 `admin-authz` 日志 + counter 增量
    - _Requirements: 3.1, 3.3, 3.4, 3.5_
  
  - [x] 3.4 新建 `api-gateway/.../admin/HealthHandler` 实现 `GET /api/v1/admin/health`
    - 并行 GET 各下游 `/actuator/health` + Redis ping，2 秒超时映射 DOWN，1 秒内 5xx 映射 DEGRADED
    - 返回 `{overall, components}` 形如 design §`/api/v1/admin/health` 章节
    - `overall = DOWN` 当且仅当 auth/memory/redis 任一 DOWN；`DEGRADED` 当任一 DEGRADED 或非关键 DOWN；否则 `UP`
    - 通过 `AdminGuardFilter`，仅 ADMIN 可访问
    - _Requirements: 18.4_
  
  - [x]* 3.5 为 `HealthHandler` 编写 WebFlux 测试
    - mock 5 个下游响应：全 UP / 一个 DEGRADED / auth-service DOWN
    - 验证 `overall` 三种判定
    - _Requirements: 18.4_

- [x] 4. 在所有后端服务的 `SecurityConfig` 加入 `/api/v1/admin/**` 规则
  - [x] 4.1 修改 `auth-service/.../security/SecurityConfig`
    - 在 `anyRequest().authenticated()` 之前插入 `requestMatchers("/api/v1/admin/**").hasRole("ADMIN")`
    - 对 `POST /api/v1/admin/users/*/role` 单独使用 `.access(...)` lambda：携带 `X-Bootstrap-Secret` 放行至 service 层精校；否则要求 `ROLE_ADMIN`
    - _Requirements: 3.2, 1.5_
  
  - [x] 4.2 修改 `memory-service/.../security/SecurityConfig` 加 `/api/v1/admin/**` → `hasRole('ADMIN')`
    - _Requirements: 3.2_
  
  - [x] 4.3 修改 `resonance-service/.../security/SecurityConfig` 加 `/api/v1/admin/**` → `hasRole('ADMIN')`
    - 在 pom 同时添加 `spring-boot-starter-data-redis` + `spring-boot-starter-cache`（为后续聚合端点准备）
    - _Requirements: 3.2_
  
  - [x] 4.4 修改 `asset-service/.../security/SecurityConfig` 加 `/api/v1/admin/**` → `hasRole('ADMIN')`
    - _Requirements: 3.2_
  
  - [x] 4.5 修改 `ai-service/.../security/SecurityConfig` 加 `/api/v1/admin/**` → `hasRole('ADMIN')`
    - _Requirements: 3.2_

- [x] 5. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. 在 `auth-service` 实现 admin 端点（bootstrap + active-users + Feign clients）
  - [x] 6.1 新建 `AdminBootstrapService` 实现 promote 完整算法
    - 读取 `${mnemoscape.admin.bootstrap-secret:}`；`@PostConstruct` 在为空时输出 WARN（R1.6）
    - 实现 disabled / secret-mismatch / missing-secret / not-found / already-admin / promoted 六个分支
    - 使用 `MessageDigest.isEqual(byte[], byte[])` 做 secret 常量时间比较
    - 写 `admin-audit` 日志 + `mnemoscape.admin.bootstrap.attempts` counter（按 result tag）
    - _Requirements: 1.4, 1.5, 1.6_
  
  - [x]* 6.2 为 `AdminBootstrapService` 编写单元测试覆盖六个分支
    - env 未设 + 非 ADMIN caller → `BOOTSTRAP_DISABLED`
    - secret 不匹配且非 ADMIN → `BOOTSTRAP_REJECTED`
    - 缺 secret 且非 ADMIN → `BOOTSTRAP_REJECTED`
    - target user 不存在 → `USER_NOT_FOUND`
    - 已是 ADMIN → `already-admin`（不写库）
    - 升级路径 → `promoted` + verify save 调用
    - _Requirements: 1.4, 1.5, 1.6_
  
  - [x]* 6.3 为 bootstrap 幂等性编写 jqwik 属性测试
    - **Property 11: Bootstrap 幂等**
    - **Validates: Requirements 1.4**
  
  - [x] 6.4 新建 `AdminUserController.promoteRole` + `RolePromotionRequest` / `RolePromotionResponse` record
    - `POST /api/v1/admin/users/{userId}/role`
    - body 验证 `role == "ADMIN"`，否则 400 `INVALID_ROLE`
    - 失败映射到对应 `ApiResponse` HTTP 码与 message
    - _Requirements: 1.4_
  
  - [x] 6.5 新建 `MemoryServiceClient` Feign 接口
    - `@FeignClient(name = "memory-service")`
    - 方法 `activeUserCounts(@RequestParam Dimension, @RequestParam(required=false) String from, @RequestParam(required=false) String to)` 返回 `ApiResponse<List<ActiveUserBucket>>`
    - 在 `auth-service` `pom.xml` 确认 `spring-cloud-starter-openfeign` + `spring-cloud-starter-loadbalancer` 都已存在
    - 5 秒超时，失败抛 `UpstreamUnavailableException`
    - _Requirements: 6.6, 6.7_
  
  - [x] 6.6 新建 `AdminStatsController.activeUsers` + `AdminStatsService.aggregateActiveUsers`
    - 解析 dimension（用 `TimeDimensionCodec.tryParse`）→ 失败 400 `INVALID_DIMENSION`
    - 解析 from / to（用 `IsoDateCodec.tryParse`）；`from > to` 或桶数 > 366 → 400 `INVALID_RANGE`
    - 缺省窗口按维度填充：DAILY 30 天 / WEEKLY 12 周 / MONTHLY 12 月 / YEARLY 5 年
    - `@Cacheable("admin.active-users", key=QueryHasher...)` TTL 60s
    - 调 `MemoryServiceClient.activeUserCounts`；失败 502 `UPSTREAM_UNAVAILABLE`（关键依赖，不降级）
    - 响应通过 `ApiResponse` 包裹，写 `AdminAuditLogger`
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 13.1, 13.3, 13.5, 14.1, 14.2_
  
  - [x]* 6.7 为 `active-users` 端点编写控制器集成测试
    - dimension 缺失 / 非法 → 400 `INVALID_DIMENSION`
    - from > to / 范围超 366 桶 → 400 `INVALID_RANGE`
    - 缺省窗口：DAILY 返回 30 桶 / WEEKLY 12 桶 / MONTHLY 12 桶 / YEARLY 5 桶
    - mock Memory_Service Feign 抛错 → 502 `UPSTREAM_UNAVAILABLE`
    - 验证 `admin-audit` 日志被写入
    - _Requirements: 6.2, 6.3, 6.4, 6.5, 6.7_
  
  - [x] 6.8 新增 `POST /api/v1/users/batch-usernames` 端点 + `AuthServiceClient` Feign 接口
    - 端点接受 `List<String> userIds`（≤ 100），返回 `Map<userId, username>`
    - 仅返回 `username`，不返回 email / passwordHash 等
    - `AuthServiceClient` 放在 `common` 或 `memory-service` 客户端包，供 top-contributors 使用
    - _Requirements: 10.2, 10.4, 15.2_
  
  - [x]* 6.9 为 `batch-usernames` 端点编写单元测试
    - 输入 ≤100 用户 ids → 返回 username 映射；不存在的 id 不出现在 map 中
    - 验证响应中不含 email / passwordHash
    - _Requirements: 10.2, 10.4_

- [x] 7. 在 `memory-service` 实现 admin 聚合端点（trends / emotion / heatmap / contributors / fragments + 内部 active-user-counts）
  - [x] 7.1 新建 `memory-service/.../admin/config/AdminCacheConfig`
    - 注册 `RedisCacheManager` + 8 个 cache name 的 per-cache TTL（按 design §Caching Strategy）
    - 注入 `BypassOnFailureCacheManager` 作为 `@Primary CacheManager`
    - _Requirements: 14.1, 14.2, 14.3_
  
  - [x] 7.2 新建 `memory-service/.../admin/util/TimeBucketing` 工具类
    - 实现 `bucketStart` / `nextBucketStart` / `formatBucket` / `parseBucket` / `bucketSeries` / `requireBucketCountWithinLimit` / `defaultFrom` / `zeroFillBuckets` 全部方法
    - 严格按 design §Time Bucketing Algorithm 的对齐规则：DAILY UTC 00:00；WEEKLY ISO 周一 00:00；MONTHLY 月首；YEARLY 年首
    - bucket key 格式 DAILY=`YYYY-MM-DD` / WEEKLY=`YYYY-Www` / MONTHLY=`YYYY-MM` / YEARLY=`YYYY`
    - _Requirements: 6.1, 6.4, 6.5, 6.6, 7.1_
  
  - [x]* 7.3 为 `TimeBucketing` 桶序长度编写 jqwik 属性测试
    - **Property 4: 桶序长度 = 窗口桶数**
    - **Validates: Requirements 6.1, 7.1**
  
  - [x]* 7.4 为 `TimeBucketing` 桶严格升序编写 jqwik 属性测试
    - **Property 5: 桶严格升序**
    - **Validates: Requirements 6.1, 7.1**
  
  - [x]* 7.5 为 `TimeBucketing` 零填充非负编写 jqwik 属性测试
    - **Property 6: 零填充非负**
    - **Validates: Requirements 6.1, 7.1**
  
  - [x]* 7.6 为 `TimeBucketing.defaultFrom` 维度对应编写 jqwik 属性测试
    - **Property 10: Default window 维度对应**
    - **Validates: Requirements 6.5**
  
  - [x] 7.7 新增 `MemoryRepository` admin 查询方法
    - `findActiveUserRowsBetween(LocalDateTime, LocalDateTime)` 返回 `(userId, latestActivityAt)`
    - `findTrendRows(LocalDateTime, LocalDateTime)` 返回 `(id, createdAt, updatedAt)`
    - `findTopContributors(LocalDateTime, LocalDateTime, Pageable)` 返回 `(userId, memoryCount)`
    - 原生 SQL `heatmapBuckets(double step)` 返回 `(latBucket, lngBucket, rawCount)`
    - 原生 SQL emotion-distribution 返回 8 个 AVG + sampleSize
    - JPQL `aggregateFragmentByType()` / `aggregateFragmentOverall()`
    - _Requirements: 6.6, 7.2, 8.2, 9.2, 9.3, 10.1, 11.1, 11.3, 15.3_
  
  - [x] 7.8 新建 `AdminStatsController` 实现 `GET /admin/stats/active-user-counts` 内部端点
    - 仅供 auth-service Feign 调用
    - 解析 `dimension` / `from` / `to`，调用 `TimeBucketing.requireBucketCountWithinLimit`
    - 用 `MemoryRepository.findActiveUserRowsBetween` 拿 rows
    - 调 `aggregateActiveUserBuckets(dim, from, to)` → `zeroFillBuckets` 输出 `List<ActiveUserBucket>`
    - `@Cacheable("admin.active-users")` TTL 60s
    - _Requirements: 6.6, 14.1, 14.2_
  
  - [x] 7.9 实现 `GET /admin/stats/memory-trends` 端点
    - `AdminStatsService.aggregateMemoryTrendBuckets` 按 design §Time Bucketing 算法处理 created/modified
    - 同样的 dimension / range 校验复用 active-users 校验链
    - `@Cacheable("admin.memory-trends")` TTL 60s
    - 响应 record `MemoryTrendBucket(String bucket, long createdCount, long modifiedCount)`
    - _Requirements: 7.1, 7.2, 7.3, 13.1, 14.1, 14.2_
  
  - [x]* 7.10 为 memory-trends 桶聚合编写单元测试
    - 构造 5 条 memories，3 条 createdAt 在桶内，2 条 updatedAt > createdAt 在桶内
    - 校验 `createdCount` / `modifiedCount` 各自计数
    - _Requirements: 7.2_
  
  - [x] 7.11 实现 `GET /admin/stats/emotion-distribution` 端点 + `EmotionDistributionAggregator`
    - 原生 SQL `JSON_EXTRACT` 八维 AVG + COUNT，仅 `privacy_level = 'PUBLIC'` 且 `emotion_profile IS NOT NULL`
    - sampleSize = 0 时全部分量返回 `0.0`
    - 响应 record `EmotionDistribution(double joy, double sadness, double anger, double fear, double surprise, double nostalgia, double peace, double melancholy, long sampleSize)`
    - `@Cacheable("admin.emotion-distribution")` TTL 120s
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 14.1, 14.2, 15.3_
  
  - [x]* 7.12 为 emotion-distribution 编写单元测试
    - sampleSize = 0 → 八个分量都是 0.0
    - 验证响应仅含九个允许字段（白名单）
    - _Requirements: 8.3, 15.1_
  
  - [x] 7.13 新建 `HeatmapAggregator` + `GET /admin/stats/heatmap` 端点
    - 解析 `gridResolution ∈ {LOW, MEDIUM, HIGH}`，映射 step `{5.0, 1.0, 0.25}`
    - 原生 SQL `FLOOR(memory_lat/:step)*:step + step/2` 量化网格中心
    - Java 层归一化：`maxRaw → 1.0`，其它按比例
    - 输出按 (lat, lon) 升序稳定排序
    - 响应 `record HeatmapPoint(double lat, double lon, double intensity)`
    - `@Cacheable("admin.heatmap")` TTL 900s
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 14.1, 14.2, 15.3_
  
  - [x]* 7.14 为 heatmap intensity 归一化编写 jqwik 属性测试
    - **Property 7: Heatmap intensity 归一化**
    - **Validates: Requirements 9.4**
  
  - [x]* 7.15 为 heatmap 网格中心对齐编写 jqwik 属性测试
    - **Property 8: Heatmap 网格中心对齐**
    - **Validates: Requirements 9.3**
  
  - [x]* 7.16 为 `HeatmapPoint` JSON 序列化编写 jqwik round-trip 属性测试
    - **Property 3: Heatmap JSON round-trip**
    - **Validates: Requirements 17.5**
  
  - [x] 7.17 实现 `GET /admin/stats/top-contributors` 端点 + `AuthServiceClient.batchUsernames` 调用
    - 解析 `limit`（默认 10，capped 100，非正抛 400 `INVALID_LIMIT`）
    - 解析 `from` / `to`（缺省 30 天回看）
    - JPQL `findTopContributors` 拿 `(userId, memoryCount)` → 调 `AuthServiceClient.batchUsernames`
    - **降级路径**：Feign 失败时所有 `username` fallback 到 `userId.substring(0,8)`，响应顶层 `degraded: true` + `degradedReasons: ["auth-service username lookup failed"]`
    - 响应 record `TopContributor(String userId, String username, long memoryCount)` —— 严格白名单
    - `@Cacheable("admin.top-contributors")` TTL 120s
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 13.1, 14.1, 14.2, 15.2, 18.1, 18.3_
  
  - [x]* 7.18 为 top-contributors 编写单元测试
    - 验证 limit cap 100；limit 为 0 / 负数 → 400 `INVALID_LIMIT`
    - mock `AuthServiceClient` 抛 Feign 异常 → 验证 `degraded=true` + fallback username + `mnemoscape.admin.degraded` counter
    - 响应 JSON grep `"email"` / `"passwordHash"` 计数为 0
    - _Requirements: 10.3, 10.4, 15.2, 18.1, 18.3_
  
  - [x] 7.19 实现 `GET /admin/stats/fragment-discovery` 端点（总 + groupBy=fragmentType）
    - JPQL 全局聚合 `(totalFragments, discoveredFragments)`，`discoveryRate = total==0 ? 0.0 : discovered/total`
    - JPQL 按 `fragmentType` 分组聚合
    - `groupBy=fragmentType` 时返回 `List<FragmentDiscoveryByType>`，否则单条 `FragmentDiscoveryOverall`
    - `@Cacheable("admin.fragment-discovery")` key 含 `groupBy`，TTL 120s
    - _Requirements: 11.1, 11.2, 11.3, 14.1, 14.2_
  
  - [x]* 7.20 为 fragment-discovery 编写单元测试
    - `totalFragments == 0` → `discoveryRate == 0.0`
    - `groupBy=fragmentType` 返回每个类型一行
    - _Requirements: 11.1, 11.2, 11.3_
  
  - [x] 7.21 在 `memory-service/.../admin` 包内为 9 个端点接入 `AdminAuditLogger` + `AdminMetrics` 切面
    - 用 `@Aspect` 或控制器层手动调用：`AdminAuditLogger.logAccess(adminUserId, endpoint, queryHash, status, latencyMs)`
    - 记录 `cacheHits` / `cacheMisses` 由 `BypassCache.get` 命中分支累计；`uncachedLatency` 由 service 方法计时
    - degraded 响应触发 `mnemoscape.admin.degraded` counter + `admin-degradation` WARN 日志
    - `adminUserId` 从 `X-User-Id` 头解析（gateway 注入）
    - _Requirements: 13.5, 14.5, 15.4, 15.5, 18.1, 18.3_
  
  - [x]* 7.22 为所有 admin DTO 编写"白名单序列化"单元测试
    - 序列化 ActiveUserBucket / MemoryTrendBucket / EmotionDistribution / HeatmapPoint / TopContributor / FragmentDiscoveryOverall / FragmentDiscoveryByType / ResonanceOverview / ResonanceTopEdge
    - grep 输出 JSON 不含 `"title"` / `"description"` / `"visualData"` / `"audioData"` / `"emotionProfile"` / `"email"` / `"passwordHash"` / `"avatarUrl"` / `"backgroundImageUrl"` / `"changeDescription"`
    - **Property 9: Privacy 不泄漏**
    - **Validates: Requirements 15.1, 15.2**

- [x] 8. 在 `resonance-service` 实现 admin 端点
  - [x] 8.1 新增 `ResonanceSpaceRepository` admin 查询方法
    - 原生 SQL `SELECT COUNT(*), AVG(similarity_score), status FROM resonance_spaces GROUP BY status`
    - JPQL `SELECT r FROM ResonanceSpace r ORDER BY r.similarityScore DESC` + `Pageable`
    - _Requirements: 12.1, 12.2_
  
  - [x] 8.2 新建 `AdminResonanceService` + `AdminResonanceController`
    - `GET /admin/stats/resonance-overview` 返回 `ResonanceOverview(long totalEdges, double averageScore, Map<String, Long> statusBreakdown)`，`@Cacheable("admin.resonance-overview")` TTL 120s
    - `GET /admin/stats/resonance-top?limit=N` 返回 `List<ResonanceTopEdge(String memoryAId, String memoryBId, double resonanceScore, String status, OffsetDateTime createdAt)>`；`limit` 默认 20、capped 100；`@Cacheable("admin.resonance-top")` key 含 limit，TTL 120s
    - Mapper 把 entity 字段 `memoryId1/memoryId2/similarityScore` 改名为 `memoryAId/memoryBId/resonanceScore`
    - DTO 严格白名单：不含 memory title / description / scene_data_url 等
    - 通过 `AdminAuditLogger` 写审计 + `AdminMetrics` 暴露 cache hit / miss / latency
    - _Requirements: 12.1, 12.2, 12.3, 13.1, 14.1, 14.2, 15.1, 15.3_
  
  - [x]* 8.3 为 resonance admin 端点编写单元测试
    - overview 返回三个 KPI 字段；statusBreakdown 包含数据库中所有状态值
    - top limit 默认 20；超过 100 被截断；非正抛 400 `INVALID_LIMIT`
    - 响应 JSON 不含 `"title"` / `"description"` / `"sceneDataUrl"`
    - _Requirements: 12.1, 12.2, 12.3, 15.1_

- [x] 9. Checkpoint - Ensure backend tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 10. 前端基础设施：auth store / 路由守卫 / `<AdminPanel>` / i18n / `useAdminApi`
  - [x] 10.1 修改 `frontend/src/stores/auth.ts` 加 `role: 'USER' | 'ADMIN'` 字段与 `isAdmin` computed
    - `toUser` 映射时 `role: profile.role === 'ADMIN' ? 'ADMIN' : 'USER'`（兜底）
    - `AuthResponse` / `UserProfileResponse` TS 类型同步加 `role`
    - 暴露 `isAdmin = computed(() => user.value?.role === 'ADMIN')`
    - _Requirements: 4.6, 2.1_
  
  - [x]* 10.2 为 auth store role 兜底编写 vitest 测试
    - `setAuth({ role: undefined })` → `user.role === 'USER'`
    - `setAuth({ role: 'ADMIN' })` → `isAdmin === true`
    - _Requirements: 4.6_
  
  - [x] 10.3 新建 `frontend/src/stores/toast.ts` 极轻量 Pinia store
    - `push({ key, tone })` / `dismiss(id)`，用于 R4.3 toast、R5.5 错误卡、R18.2 降级 badge 复用
    - _Requirements: 4.3, 5.5, 18.2_
  
  - [x] 10.4 修改 `frontend/src/router/index.ts` 加 `/admin/**` 路由树 + `requiresAdmin` 守卫
    - 注册 `/admin` + 9 个子路由（home / active-users / memory-trends / emotion / heatmap / top-contributors / fragments / resonance / health）
    - `meta.requiresAuth` + `meta.requiresAdmin`，懒加载视图
    - `beforeEach` 守卫：未登录跳 `/login` 带 `redirect`；USER 跳 `/memories` 并 push toast `admin.guard.notAdmin`
    - _Requirements: 4.1, 4.2, 4.3_
  
  - [x]* 10.5 为路由守卫编写 vitest 测试
    - 未登录访问 `/admin/foo` → `/login?redirect=/admin/foo`
    - USER 访问 `/admin` → `/memories` + toast push
    - ADMIN 访问 `/admin` → 通过
    - _Requirements: 4.1, 4.2, 4.3_
  
  - [x] 10.6 修改 `frontend/src/components/AppHeader.vue` 在 `auth.isAdmin` 时渲染管理员入口 RouterLink
    - 文案 `t('admin.nav.entry')`；含盾形 SVG 图标
    - 仅 `isAdmin === true` 时挂载（v-if，不是 v-show）
    - _Requirements: 4.4, 4.5_
  
  - [x] 10.7 在 `frontend/src/i18n/locales/zh-CN.json` 与 `en-US.json` 中新增 `admin.*` 命名空间全部 key
    - 完全照 design §Bilingual i18n Plan 表格落实：`admin.nav.*` / `admin.guard.*` / `admin.common.*` / `admin.errors.*` / `admin.activeUsers.*` / `admin.memoryTrends.*` / `admin.emotion.*` / `admin.heatmap.*` / `admin.contributors.*` / `admin.fragments.*` / `admin.resonance.*` / `admin.health.*`
    - 两个文件 key 集合必须完全相同
    - _Requirements: 16.1, 16.2, 16.3_
  
  - [x] 10.8 新建 `frontend/src/api/admin.ts` axios 客户端封装
    - 9 个端点的 typed fetch 函数：`getActiveUsers` / `getMemoryTrends` / `getEmotionDistribution` / `getHeatmap` / `getTopContributors` / `getFragmentDiscovery` / `getResonanceOverview` / `getResonanceTop` / `getAdminHealth`
    - 沿用既有 axios instance 自动注入 `Authorization: Bearer <token>` 与 `Accept-Language`
    - 返回类型严格匹配后端 record 结构
    - _Requirements: 13.1, 13.3, 13.4_
  
  - [x] 10.9 新建 `frontend/src/composables/useAdminApi.ts` 通用状态机 composable
    - 暴露 `data / loading / error / degraded / degradedReasons / fetch / reset`
    - 解析 `ApiResponse<T>` 外壳；扁平化 `data.degraded` / `data.degradedReasons` 到 state
    - 错误码翻译：401 → 触发 `authStore.logout` + `/login`；403 / 502 / 网络 → 通过 `t('admin.errors.<CODE>')` 翻译
    - _Requirements: 5.4, 5.5, 13.1, 13.4, 18.1, 18.2_
  
  - [x]* 10.10 为 `useAdminApi` 编写 vitest + fast-check 测试
    - mock axios 返回 `{code: 502, message: 'UPSTREAM_UNAVAILABLE'}` → `state.error.code === 'UPSTREAM_UNAVAILABLE'`
    - mock 200 + `degraded: true` → state.degraded === true 且 data 仍可用
    - mock 401 → 验证 `authStore.logout` 与 `router.push('/login')`
    - _Requirements: 5.5, 13.1, 18.1, 18.2_
  
  - [x] 10.11 新建 `frontend/src/components/admin/AdminPanel.vue` 共享外壳组件
    - props: `title` / `state ('idle'|'loading'|'empty'|'error'|'ready')` / `error` / `degraded` / `degradedReasons` / `onRetry`
    - 渲染 5 态：loading 骨架 / empty / error 卡（含错误码 + 重试按钮）/ ready 默认 slot / degraded badge & footer
    - CSS 使用 `var(--app-header-h, 88px)` 在面板独立成页时让位
    - _Requirements: 5.4, 5.5, 5.6, 18.2_
  
  - [x]* 10.12 为 `<AdminPanel>` 状态机编写 vitest 测试
    - 5 态各自的 DOM 结构断言
    - degraded badge 与 footer 渲染条件
    - 错误态点击 retry 调用 `onRetry`
    - _Requirements: 5.5, 5.6, 18.2_

- [x] 11. 前端 8 个面板视图与 composable
  - [x] 11.1 新建 `views/admin/AdminEntryView.vue` + `views/admin/AdminHomeView.vue` 网格布局
    - 12 列响应式 grid（≥1280px）；< 768px 单列堆叠
    - 用 `<AdminPanelCell>` 在首页缩略嵌入 8 个 panel 摘要
    - 在 `frontend/package.json` 加 `"echarts": "5.5.1"` / `"vue-echarts": "7.0.3"` / `"@deck.gl/aggregation-layers": "^9.3.0"`
    - _Requirements: 5.1, 5.2, 5.3_
  
  - [x] 11.2 实现 `composables/useAdminActiveUsers.ts` + `views/admin/ActiveUsersView.vue`
    - dimension toggle（DAILY/WEEKLY/MONTHLY/YEARLY）四选一；watch 触发重 fetch
    - 切换 dimension 时若现有 `from/to` 仍合法则保留，否则重置为缺省窗口
    - 用 ECharts line/column 渲染；toggle 标签来自 `admin.activeUsers.dimensions.*`
    - _Requirements: 6.8, 6.9_
  
  - [x]* 11.3 为 `ActiveUsersView` ECharts option 构造编写 vitest 测试
    - 数据传入后 `option.series[0].data.length === bucket count`
    - x 轴类目数 = bucket count
    - _Requirements: 6.8_
  
  - [x] 11.4 实现 `composables/useAdminMemoryTrends.ts` + `views/admin/MemoryTrendsView.vue`
    - ECharts stacked column；`series.stack='total'` 区分 created vs modified
    - 复用 dimension toggle 行为
    - _Requirements: 7.4_
  
  - [x] 11.5 实现 `composables/useAdminEmotion.ts` + `views/admin/EmotionDistView.vue`
    - ECharts radar，8 维 `radar.indicator` 来自 `admin.emotion.components.*`
    - 显示 `sampleSize` 文案 `t('admin.emotion.sampleSize', { count })`
    - _Requirements: 8.5_
  
  - [x] 11.6 实现 `composables/useAdminHeatmap.ts` + `views/admin/HeatmapView.vue`
    - 用 maplibre-gl `globe` projection + `MapboxOverlay` + deck.gl `HexagonLayer`
    - `radius` 按 gridResolution 取 350_000 / 80_000 / 25_000
    - 挂载时 `new ResizeObserver(() => map.resize()).observe(containerEl)`，`onUnmounted` 时 `disconnect` + `map.remove`
    - 不设 `maxBounds`；style 中写 `projection: { type: 'globe' }`
    - resolution 切换器三选一
    - _Requirements: 9.5, 9.6_
  
  - [x]* 11.7 为 `HeatmapView` ResizeObserver 生命周期编写 vitest 测试
    - mount 时验证 `ResizeObserver.observe` 被调用
    - unmount 时验证 `disconnect` 被调用
    - _Requirements: 9.6_
  
  - [x] 11.8 实现 `composables/useAdminContributors.ts` + `views/admin/ContributorsView.vue`
    - ECharts horizontal bar；`yAxis.type='category'`
    - avatar fallback 圆形占位 + 用户名首字母
    - 计数后缀使用 `t('admin.contributors.countSuffix')`
    - 不渲染响应里没有的字段（强制白名单）
    - _Requirements: 10.5_
  
  - [x] 11.9 实现 `composables/useAdminFragments.ts` + `views/admin/FragmentDiscoveryView.vue`
    - 总体探索率用 ECharts gauge；按类型用 horizontal bars
    - 类型 label 走 `t('admin.fragments.types.' + type, type)` 三参 fallback
    - _Requirements: 11.4, 16.2_
  
  - [x] 11.10 实现 `composables/useAdminResonance.ts` + `views/admin/ResonanceOverviewView.vue`
    - KPI 卡：`totalEdges` / `averageScore`
    - statusBreakdown 走 `t('admin.resonance.status.' + status, status)`
    - Top edges 用 ECharts force graph (`series.type='graph'`, `force.repulsion=200`)
    - _Requirements: 12.4, 16.2_
  
  - [x] 11.11 实现 `composables/useAdminHealth.ts` + `views/admin/SystemHealthView.vue`
    - 调 `/api/v1/admin/health`
    - 渲染 6 个组件状态卡：auth-service / memory-service / resonance-service / asset-service / ai-service / redis
    - 状态颜色：UP=绿 / DEGRADED=黄 / DOWN=红
    - 每个卡显示 `latencyMs` 与 `reason`
    - `overall` 卡置顶
    - _Requirements: 18.4_

- [x] 12. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- 标记 `*` 的子任务为可选测试任务，可在 MVP 阶段跳过
- 每个任务都引用了具体的 requirements 子条款编号，便于追溯
- 第 5 / 9 / 12 是 checkpoint，确保前一阶段的所有测试通过后再继续
- Property-based 测试（jqwik 后端，fast-check 前端）覆盖了 design.md `## Correctness Properties` 全部 12 条性质
- Bootstrap secret 流程的运维步骤（Step 7：设置 `ADMIN_BOOTSTRAP_SECRET` → 提升首位 ADMIN → 撤销 secret）不在编码任务范围内，由实施者完成代码后交付给运维
- DDL 脚本（task 2.1）需要在部署前手工执行；项目当前没有 Flyway/Liquibase，按既有惯例处理
- 所有 admin DTO 严格白名单（record 显式字段），任何敏感字段（title / description / email / passwordHash 等）禁止出现

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.3", "1.5", "1.7", "1.9", "1.11", "1.12", "2.1", "2.2", "10.7"] },
    { "id": 1, "tasks": ["1.2", "1.4", "1.6", "1.8", "1.10", "2.3", "2.4", "2.8", "10.1", "10.3", "10.11"] },
    { "id": 2, "tasks": ["2.5", "2.6", "3.1", "10.2", "10.6", "10.12"] },
    { "id": 3, "tasks": ["2.7", "3.2", "3.4", "4.1", "4.2", "4.3", "4.4", "4.5", "10.4", "10.8"] },
    { "id": 4, "tasks": ["3.3", "3.5", "6.1", "7.1", "7.2", "8.1", "10.5", "10.9"] },
    { "id": 5, "tasks": ["6.2", "6.3", "6.4", "6.5", "6.8", "7.3", "7.4", "7.5", "7.6", "7.7", "8.2", "10.10", "11.1"] },
    { "id": 6, "tasks": ["6.6", "6.9", "7.8", "7.9", "7.11", "7.13", "7.17", "7.19", "8.3", "11.2", "11.4", "11.5", "11.6", "11.8", "11.9", "11.10", "11.11"] },
    { "id": 7, "tasks": ["6.7", "7.10", "7.12", "7.14", "7.15", "7.16", "7.18", "7.20", "7.21", "11.3", "11.7"] },
    { "id": 8, "tasks": ["7.22"] }
  ]
}
```
 