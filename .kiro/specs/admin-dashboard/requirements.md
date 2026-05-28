# Requirements Document

## Introduction

「管理端大屏可视化看板」（Admin Dashboard）是 Mnemoscape 面向**管理员**的全局观测面板，用于以可视化方式呈现平台运营状态：用户活跃度、记忆创建趋势、情绪分布、全球记忆地理热力、共鸣关系、碎片探索率等核心指标。

本特性是 PROJECT-STATUS-AUDIT.md §9.6 中标记的高优先级遗留项。设计文档 `Mnemoscape-Design-Document.md` 当前仅含 §3.1 / §3.2，并无 §3.4 节，因此本规范的需求不从既有设计提取，而是在审计文档与第二代工作记忆（Memory.md v2）的语境下重新定义。

为支持本特性，平台必须先补齐**管理员角色**这一前置能力：当前 `User` 实体没有 role 列，`JwtAuthFilter`（位于 `backend/common/.../security/JwtAuthFilter.java`）将所有已认证主体一律授予 `ROLE_USER`。本规范覆盖：管理员角色的建模与授予、网关与微服务对管理员路径的鉴权、前端管理路由守卫、看板组件、各维度聚合 API 契约、隐私安全边界、性能与国际化要求。

实现细节（图表库选型 ECharts/AntV、3D 地球继续复用 maplibre-gl/deck.gl/three、Redis 缓存键格式、具体 SQL 等）将由 design.md 决定，本文档保持实现无关。

## Glossary

- **Admin_Dashboard**：本特性整体——管理员可访问的可视化看板（前端 + 后端聚合接口）的合称。
- **Admin_Frontend**：前端 Vue 3 应用中专属管理员的路由与视图集合（`/admin/...` 路径）。
- **Admin_Backend**：后端用于支撑看板的聚合端点集合，分布在 `auth-service` / `memory-service` / `resonance-service` / `asset-service` 各自的 `/admin/...` 子路径下。
- **Admin_Role**：JWT claim `role` 取值为 `"ADMIN"` 的用户身份；与之对应的另一个值为 `"USER"`。
- **Gateway**：Spring Cloud Gateway（端口 8080），在 `AuthGlobalFilter` 中校验 JWT 并注入 `X-User-Id` 与 `X-User-Role` 头。
- **Auth_Service**：端口 8081，负责用户、好友、JWT 颁发与刷新；本特性中新增管理员相关的用户聚合端点。
- **Memory_Service**：端口 8082，负责记忆 CRUD 与版本/碎片/漂移；本特性中新增记忆与碎片相关的聚合端点。
- **Resonance_Service**：端口 8085，负责共鸣大厅；本特性中新增共鸣关系聚合端点。
- **Asset_Service**：端口 8084，负责 MinIO 资产管理；本特性中新增资产体量聚合端点。
- **AI_Service**：端口 8083，负责对话与场景重建；本特性中新增情绪分布聚合端点（数据源为 `memories.emotion_profile` JSON）。
- **Time_Dimension**：聚合时间粒度的枚举，取值集合为 `{DAILY, WEEKLY, MONTHLY, YEARLY}`。
- **Aggregation_Endpoint**：返回纯统计/聚合结果（不含任何个人可识别记忆正文）的后端只读 HTTP 端点。
- **Active_User**：在指定时间窗口内至少创建/修改过一条记忆，或至少发起过一次共鸣搜索的用户。
- **Heatmap_Point**：地理热力图中的一个点，包含 `{lat, lon, intensity}` 三元组，且 `intensity` 为该网格内 PUBLIC 记忆数量的归一化值。
- **Emotion_Distribution**：8 维情绪向量（joy / sadness / anger / fear / surprise / nostalgia / peace / melancholy）在指定群体上的均值或加权分布。
- **Top_Contributor**：在指定时间窗口内创建记忆数量排名靠前的匿名化用户（仅返回 username 与计数，不返回 email/手机号等敏感字段）。
- **Fragment_Discovery_Rate**：`memory_fragments.is_discovered = true` 的碎片占总碎片数的比率。
- **Resonance_Edge**：`resonances` 表中两条记忆之间的关系，承载相似度得分。
- **Privacy_Boundary**：管理员不可见的私有记忆正文、用户邮箱明文、密码哈希等敏感字段集合。
- **Bilingual_Locale**：前端必须同时提供 `zh-CN` 与 `en-US` 两套翻译；任何新增 UI 文案均需进入两套字典。
- **Audit_Log**：管理员对 Admin_Backend 的每次访问产生的一条结构化日志记录。

## Requirements

### Requirement 1: 管理员角色的数据建模与授予

**User Story:** 作为平台运营者，我希望平台内有一个明确的"管理员"身份，使得我可以把看板入口仅授予可信账号，而不必新增一套独立账号体系。

#### Acceptance Criteria

1. THE Auth_Service SHALL persist a non-null `role` column on the `users` table whose value is one of `{"USER", "ADMIN"}`.
2. WHEN a new user registers via `POST /api/v1/auth/register`, THE Auth_Service SHALL set `role = "USER"` for the created record.
3. WHEN an existing record predates this requirement and `role` is null at read time, THE Auth_Service SHALL treat the value as `"USER"` and SHALL persist the resolved value on the next successful update of that record.
4. THE Auth_Service SHALL expose an internal command (CLI flag, SQL migration script, or `POST /api/v1/admin/users/{userId}/role` endpoint guarded by a configuration-only bootstrap secret) that promotes a single user to `role = "ADMIN"`.
5. WHERE the bootstrap secret is provided via environment variable `ADMIN_BOOTSTRAP_SECRET`, THE Auth_Service SHALL accept role-promotion requests carrying that secret in an `X-Bootstrap-Secret` header; THE Auth_Service SHALL reject the request with HTTP 403 otherwise.
6. IF the `ADMIN_BOOTSTRAP_SECRET` environment variable is unset or empty, THEN THE Auth_Service SHALL refuse all role-promotion requests with HTTP 403 and SHALL log a warning at service startup.

### Requirement 2: JWT 中携带角色与传递

**User Story:** 作为后端开发者，我希望 JWT 一次性把 role 信息透传到所有下游微服务，使得每个服务无需回头查询 Auth_Service 也能做基本鉴权。

#### Acceptance Criteria

1. WHEN the Auth_Service issues an access token via login or token refresh, THE Auth_Service SHALL include a `role` claim whose value is a case-sensitive exact match of the user's persisted role and belongs to the set `{"USER", "ADMIN"}`.
2. WHEN the Gateway successfully validates an incoming JWT, THE Gateway SHALL inject the role resolved from the JWT into the downstream request as header `X-User-Role` whose value is restricted to the case-sensitive set `{"USER", "ADMIN"}`.
3. WHEN the JwtAuthFilter (in `backend/common/.../security/JwtAuthFilter.java`) populates Spring Security's `SecurityContextHolder`, THE JwtAuthFilter SHALL grant authority `ROLE_ADMIN` if the token's `role` claim is the case-sensitive string `"ADMIN"`, and SHALL grant authority `ROLE_USER` if the token's `role` claim is the case-sensitive string `"USER"`.
4. IF the `role` claim is missing or contains a value not within the case-sensitive set `{"USER", "ADMIN"}`, THEN THE JwtAuthFilter SHALL grant authority `ROLE_USER`, SHALL allow the request to continue through the remainder of the filter chain, and SHALL emit a log entry at WARN level that includes the offending claim value or an indicator that the claim was absent.
5. WHEN the Auth_Service issues an access token via login or token refresh, THE Auth_Service SHALL preserve the existing JWT claims (`sub`, `username`, `jti`, `exp`) such that the addition of the `role` claim does not modify their values or issuance semantics.
6. IF the Gateway fails to validate an incoming JWT, THEN THE Gateway SHALL NOT inject the `X-User-Role` header into the downstream request.

### Requirement 3: 后端管理员路径的访问控制

**User Story:** 作为安全负责人，我希望任何带 `/admin/` 前缀的后端路径只对管理员可达，使得普通用户即便伪造请求也无法触达聚合数据。

#### Acceptance Criteria

1. THE Gateway SHALL reject any request whose path starts with `/api/v1/admin/` and whose `X-User-Role` is not `"ADMIN"` with HTTP 403.
2. THE Memory_Service, Auth_Service, Resonance_Service, Asset_Service, and AI_Service SHALL each register a Spring Security rule that maps `/api/v1/admin/**` to `hasRole('ADMIN')` so that direct service calls bypassing the Gateway are also rejected.
3. WHEN an unauthenticated request reaches an `/api/v1/admin/**` path, THE Gateway SHALL respond with HTTP 401.
4. WHEN an `/api/v1/admin/**` request is rejected for authorization reasons, THE Gateway SHALL include a JSON body matching the existing `ApiResponse.forbidden(message)` envelope.
5. THE Gateway SHALL log each admin path access attempt (success or failure) with userId, path, method, decision, and timestamp at INFO level or above.

### Requirement 4: 前端管理路由与守卫

**User Story:** 作为管理员用户，我希望登录后能进入 `/admin` 看到完整看板，而普通用户访问 `/admin` 时被自动重定向到自己的记忆首页，不会看到无意义的 403 弹窗。

#### Acceptance Criteria

1. THE Admin_Frontend SHALL register a top-level route at path `/admin` and child routes at `/admin/<panel-key>` for each major panel.
2. WHEN any unauthenticated visitor navigates to a path under `/admin`, THE Admin_Frontend SHALL redirect to `/login` while preserving the original path as a `redirect` query parameter.
3. WHEN a user with `role = "USER"` navigates to a path under `/admin`, THE Admin_Frontend SHALL redirect to `/memories` and SHALL surface a non-blocking i18n notification with key `admin.guard.notAdmin`.
4. WHEN a user with `role = "ADMIN"` is on any non-admin page, THE Admin_Frontend SHALL render an entry link in the AppHeader pointing to `/admin`.
5. WHERE the active user has `role = "USER"`, THE Admin_Frontend SHALL NOT render the AppHeader admin entry link.
6. THE Admin_Frontend SHALL share the existing Pinia auth store and SHALL NOT introduce a parallel authentication layer.

### Requirement 5: 看板首页布局与组件骨架

**User Story:** 作为管理员，我希望 `/admin` 首页一屏呈现核心 KPI 卡片与多个可视化图表，使得我无需翻页就能掌握平台健康度。

#### Acceptance Criteria

1. THE Admin_Frontend SHALL render the `/admin` index view as a responsive grid containing at minimum the following panels: 总用户数卡片、活跃用户多维度切换看板、记忆创建趋势、情绪分布、全球记忆热力图、Top 贡献者、碎片探索率、共鸣关系。
2. WHEN the viewport width is at least 1280 px, THE Admin_Frontend SHALL lay out the panels on a 12-column grid.
3. WHILE the viewport width is below 768 px, THE Admin_Frontend SHALL collapse all panels to a single-column stack.
4. THE Admin_Frontend SHALL render every panel as an independent component that fetches its own data and displays its own loading, empty, and error states.
5. WHEN any panel's data request fails, THE Admin_Frontend SHALL render an in-panel error card containing the error code and a localized retry button, and SHALL NOT block sibling panels from rendering.
6. THE Admin_Frontend SHALL render every panel header using the `var(--app-header-h, 88px)` offset convention so the page does not collide with the global AppHeader.

### Requirement 6: 活跃用户多维度切换看板

**User Story:** 作为管理员，我希望切换 DAILY / WEEKLY / MONTHLY / YEARLY 维度查看活跃用户曲线，使得我能识别短期波动与长期趋势。

#### Acceptance Criteria

1. THE Auth_Service SHALL expose `GET /api/v1/admin/stats/active-users?dimension={DAILY|WEEKLY|MONTHLY|YEARLY}&from={ISO_DATE}&to={ISO_DATE}`, accessible only to authenticated callers carrying the ADMIN role, returning within 3 seconds a JSON list of `{bucket, activeUserCount}` ordered by `bucket` ascending and containing exactly one entry per bucket in the requested range (zero-filled when no active users exist for that bucket).
2. IF the request lacks ADMIN authorization, THEN THE Auth_Service SHALL respond with HTTP 401 when the caller is unauthenticated or HTTP 403 when the caller is authenticated but lacks the ADMIN role, and SHALL NOT return any aggregation data.
3. IF the `dimension` parameter is missing or not in the allowed set `{DAILY, WEEKLY, MONTHLY, YEARLY}`, THEN THE Auth_Service SHALL respond with HTTP 400 and an error code `INVALID_DIMENSION` indicating the offending parameter.
4. IF `from` or `to` is provided but cannot be parsed as an ISO_DATE (`YYYY-MM-DD`), OR `from` is later than `to`, OR the requested range would yield more than 366 buckets at the chosen dimension, THEN THE Auth_Service SHALL respond with HTTP 400 and an error code `INVALID_RANGE` indicating the violation.
5. IF both `from` and `to` are omitted, THEN THE Auth_Service SHALL set `to` to the current server date in UTC and SHALL set `from` to the dimension-specific window prior to `to` (DAILY → 30 days, WEEKLY → 12 weeks, MONTHLY → 12 months, YEARLY → 5 years).
6. THE Auth_Service SHALL define `Active_User` for a bucket as a user whose latest `memories.created_at` or `memories.modified_at` falls within that bucket's UTC time window (bucket boundaries aligned to UTC midnight for DAILY, ISO week start Monday 00:00 UTC for WEEKLY, first day of month 00:00 UTC for MONTHLY, first day of year 00:00 UTC for YEARLY), and SHALL retrieve the underlying counts via a Feign call to the Memory_Service.
7. IF the Memory_Service call fails or does not respond within 5 seconds, THEN THE Auth_Service SHALL respond with HTTP 502 and an error code `UPSTREAM_UNAVAILABLE`, and SHALL NOT return partial or stale aggregation data.
8. THE Admin_Frontend SHALL render the result as a line or column chart with a dimension-toggle control exposing exactly four options (DAILY, WEEKLY, MONTHLY, YEARLY) whose labels are sourced from i18n keys under `admin.activeUsers.dimensions.*`.
9. WHEN the user changes the dimension, THE Admin_Frontend SHALL refetch using the new dimension, retaining the current `from` and `to` values when they remain valid for the new dimension's 366-bucket limit and otherwise replacing them with the default window defined in criterion 5.

### Requirement 7: 记忆创建趋势看板

**User Story:** 作为管理员，我希望以同样的多维度时间粒度查看"每个时段新增了多少条记忆"，使得我能评估内容增长。

#### Acceptance Criteria

1. THE Memory_Service SHALL expose `GET /api/v1/admin/stats/memory-trends?dimension={DAILY|WEEKLY|MONTHLY|YEARLY}&from={ISO_DATE}&to={ISO_DATE}` returning a list of `{bucket, createdCount, modifiedCount}` ordered by `bucket` ascending.
2. THE Memory_Service SHALL count `createdCount` as records inserted into `memories` within the bucket and SHALL count `modifiedCount` as records whose `modified_at` falls within the bucket and exceeds `created_at`.
3. THE Memory_Service SHALL apply the same dimension validation, default windows, and `INVALID_DIMENSION` / `INVALID_RANGE` error codes defined in Requirement 6.
4. THE Admin_Frontend SHALL render the result as a stacked or grouped chart distinguishing creation versus modification.

### Requirement 8: 情绪分布看板

**User Story:** 作为管理员，我希望看到平台所有 PUBLIC 记忆的情绪向量整体分布，使得我能了解用户群体的情感倾向。

#### Acceptance Criteria

1. THE Memory_Service SHALL expose `GET /api/v1/admin/stats/emotion-distribution?from={ISO_DATE}&to={ISO_DATE}` returning a JSON object whose keys are exactly `{joy, sadness, anger, fear, surprise, nostalgia, peace, melancholy}` and whose values are floats in the inclusive range `[0.0, 1.0]`.
2. THE Memory_Service SHALL compute each component as the mean of the corresponding key inside `memories.emotion_profile` JSON, considering only records with `privacy = 'PUBLIC'` and a non-null `emotion_profile`.
3. WHEN the filtered record count is zero, THE Memory_Service SHALL return all eight components as `0.0` along with a top-level field `sampleSize: 0`.
4. THE Memory_Service SHALL include a top-level `sampleSize` integer in the response equal to the number of records that contributed to the aggregation.
5. THE Admin_Frontend SHALL render the eight components as a radar chart, bar chart, or comparable shape with localized component labels under `admin.emotion.components.*`.

### Requirement 9: 全球记忆热力图

**User Story:** 作为管理员，我希望在 3D 地球上看到全球 PUBLIC 记忆的密度热力，使得我能直观理解地理分布。

#### Acceptance Criteria

1. THE Memory_Service SHALL expose `GET /api/v1/admin/stats/heatmap?gridResolution={LOW|MEDIUM|HIGH}` returning a list of Heatmap_Point items.
2. THE Memory_Service SHALL include only records where `privacy = 'PUBLIC'` AND `memory_lat` is not null AND `memory_lon` is not null.
3. THE Memory_Service SHALL bucket points to a geohash or fixed-resolution lat/lon grid where `LOW` corresponds to approximately 5 degrees, `MEDIUM` to approximately 1 degree, and `HIGH` to approximately 0.25 degrees.
4. THE Memory_Service SHALL normalize `intensity` so that the maximum value across the response equals `1.0` and all other values are proportional.
5. THE Admin_Frontend SHALL render the heatmap as a 3D layer over a globe surface using only libraries already present in `frontend/package.json` at the time of design (i.e. maplibre-gl, deck.gl, or three).
6. WHILE the heatmap canvas is mounting, THE Admin_Frontend SHALL attach a ResizeObserver to the canvas container so the layer renders correctly even if the container starts with zero dimensions.

### Requirement 10: Top 贡献者看板

**User Story:** 作为管理员，我希望看到记忆创建数最多的若干用户，使得我能识别核心活跃创作者。

#### Acceptance Criteria

1. THE Memory_Service SHALL expose `GET /api/v1/admin/stats/top-contributors?limit={N}&from={ISO_DATE}&to={ISO_DATE}` returning a list of `{userId, username, memoryCount}` ordered by `memoryCount` descending.
2. THE Memory_Service SHALL fetch `username` from the Auth_Service via Feign rather than reading the auth database directly.
3. WHERE `limit` is omitted, THE Memory_Service SHALL default `limit` to `10` and SHALL cap any caller-supplied value at `100`.
4. THE Memory_Service SHALL NOT include user email, password hash, or any field outside `{userId, username, memoryCount}` in the response.
5. THE Admin_Frontend SHALL render the result as a ranked list with avatar fallback and localized count suffix.

### Requirement 11: 碎片探索率看板

**User Story:** 作为管理员，我希望知道记忆碎片整体的探索率，使得我能评估记忆探索玩法的活跃度。

#### Acceptance Criteria

1. THE Memory_Service SHALL expose `GET /api/v1/admin/stats/fragment-discovery` returning `{totalFragments, discoveredFragments, discoveryRate}` where `discoveryRate = discoveredFragments / totalFragments` and is a float in `[0.0, 1.0]`.
2. WHEN `totalFragments` is zero, THE Memory_Service SHALL return `discoveryRate` as `0.0`.
3. THE Memory_Service SHALL additionally expose a per-fragment-type breakdown via `GET /api/v1/admin/stats/fragment-discovery?groupBy=fragmentType` returning a list of `{fragmentType, totalFragments, discoveredFragments, discoveryRate}`.
4. THE Admin_Frontend SHALL render the overall rate as a gauge or radial progress and SHALL render the per-type breakdown as horizontal bars.

### Requirement 12: 共鸣关系看板

**User Story:** 作为管理员，我希望看到平台内共鸣关系的总量、平均得分与最强共鸣对，使得我能评估共鸣特性的健康度。

#### Acceptance Criteria

1. THE Resonance_Service SHALL expose `GET /api/v1/admin/stats/resonance-overview` returning `{totalEdges, averageScore, statusBreakdown}` where `statusBreakdown` is a map from each `resonances.status` enum value to its count.
2. THE Resonance_Service SHALL expose `GET /api/v1/admin/stats/resonance-top?limit={N}` returning the top `N` Resonance_Edge items ordered by `resonance_score` descending, with `N` defaulting to `20` and capped at `100`.
3. THE Resonance_Service SHALL include only `{memoryAId, memoryBId, resonanceScore, status, createdAt}` in the top-edge response and SHALL NOT include any memory title, description, or other content body.
4. THE Admin_Frontend SHALL render the overview as KPI cards and the top edges as a force-directed graph or table at the implementer's discretion.

### Requirement 13: 聚合 API 的统一契约

**User Story:** 作为前端开发者，我希望所有 admin 聚合接口的响应外壳与错误形状一致，使得每个面板的请求/重试/错误处理可以共用同一套通用代码。

#### Acceptance Criteria

1. THE Admin_Backend SHALL wrap every successful response in the existing `ApiResponse<T>` envelope used elsewhere in the project.
2. WHEN an admin endpoint encounters a downstream failure, THE Admin_Backend SHALL return an `ApiResponse` with HTTP 502 and an error code starting with the prefix `ADMIN_AGG_`.
3. THE Admin_Backend SHALL accept ISO 8601 calendar dates (`YYYY-MM-DD`) for all date-range parameters.
4. WHEN an admin endpoint receives an `Accept-Language` header whose primary subtag is `zh`, THE Admin_Backend SHALL localize human-readable error messages to Chinese; THE Admin_Backend SHALL default to English otherwise.
5. THE Admin_Backend SHALL apply the existing `X-User-Id` extraction convention (Gateway-injected) for caller identification and SHALL NOT require duplicate authentication parameters.

### Requirement 14: 性能与缓存

**User Story:** 作为管理员，我希望看板各面板在打开 `/admin` 后两秒内显现首屏数据，使得视觉体验接近"实时"。

#### Acceptance Criteria

1. THE Admin_Backend SHALL cache each aggregation response in Redis using a key encoding endpoint path, query parameters, and dimension.
2. THE Admin_Backend SHALL apply per-endpoint TTLs: heatmap cache TTL SHALL be at least 600 seconds and at most 3600 seconds; trend cache TTL SHALL be at least 60 seconds and at most 300 seconds; KPI overview cache TTL SHALL be at least 30 seconds and at most 120 seconds.
3. WHEN the Redis cluster is unreachable, THE Admin_Backend SHALL bypass the cache and SHALL serve the live aggregation result.
4. WHEN measured against a database populated with 100,000 memories on the development middleware host, each non-heatmap endpoint SHALL return a cached response within 200 ms p95 and an uncached response within 1500 ms p95.
5. THE Admin_Backend SHALL emit Micrometer counters for cache hits, cache misses, and uncached query latency per endpoint.

### Requirement 15: 隐私与安全边界

**User Story:** 作为产品负责人，我希望看板永远只能看到聚合数据，使得我有信心向团队证明管理员特权不会侵犯用户隐私。

#### Acceptance Criteria

1. THE Admin_Backend SHALL NOT expose any field whose value is the body of a private memory: `memories.title`, `memories.description`, `memories.visual_data`, `memories.audio_data`, and `memory_versions.change_description` SHALL NOT appear in any admin endpoint response.
2. THE Admin_Backend SHALL NOT expose `users.email`, `users.password_hash`, or any field marked sensitive in the existing entity classes.
3. WHERE an admin endpoint surfaces sample memories (e.g. for the heatmap or top edges), THE Admin_Backend SHALL include only memories whose `privacy` is `PUBLIC`.
4. WHEN an admin accesses any `/api/v1/admin/**` endpoint, THE Admin_Backend SHALL append an Audit_Log entry containing `{adminUserId, endpoint, queryHash, timestamp, responseStatus}` to a dedicated logger named `admin-audit`.
5. THE Admin_Backend SHALL NOT log raw query parameters that could contain personal data; instead, THE Admin_Backend SHALL log a SHA-256 hash of the canonicalized query string as `queryHash`.

### Requirement 16: 国际化

**User Story:** 作为运营团队成员，我希望看板的所有文案都支持中英文切换，使得跨语言团队成员都能阅读。

#### Acceptance Criteria

1. THE Admin_Frontend SHALL define every user-visible string under the i18n namespace `admin.*` in both `frontend/src/i18n/locales/zh-CN.json` and `frontend/src/i18n/locales/en-US.json`.
2. WHERE a panel renders enum-derived strings (e.g. fragment types, resonance status), THE Admin_Frontend SHALL look them up via `t('admin.<panel>.<enumGroup>.${value}', value)` so missing translations fall back to the raw enum value rather than rendering blank.
3. THE Admin_Frontend SHALL NOT introduce any hard-coded English string outside the i18n dictionaries.

### Requirement 17: 序列化与解析的回程一致性

**User Story:** 作为后端开发者，我希望聚合接口在打包/解包查询参数与响应载荷时不丢失语义，使得前端无需做兼容性补丁。

#### Acceptance Criteria

1. THE Admin_Backend SHALL implement parsing of `Time_Dimension` strings into the internal enum and a pretty printer that emits the canonical uppercase form.
2. FOR ALL valid Time_Dimension enum values, parsing the printed form SHALL produce an equivalent enum value (round-trip property).
3. THE Admin_Backend SHALL implement parsing of ISO 8601 calendar date strings and a pretty printer that emits the canonical `YYYY-MM-DD` form.
4. FOR ALL valid LocalDate values, parsing the printed form SHALL produce an equivalent LocalDate (round-trip property).
5. THE Admin_Backend SHALL serialize Heatmap_Point lists to JSON and SHALL implement a deserializer; FOR ALL Heatmap_Point lists with finite numeric components, deserializing the serialized form SHALL produce an equivalent list (round-trip property).

### Requirement 18: 可观测性与降级

**User Story:** 作为运维者，我希望聚合接口在依赖服务故障时仍然返回部分数据，使得看板不会"全白"。

#### Acceptance Criteria

1. WHEN a downstream Feign call fails, THE Admin_Backend SHALL return the partial aggregation result it has computed and SHALL include a top-level `degraded: true` flag along with a `degradedReasons` string array.
2. THE Admin_Frontend SHALL render a localized "数据降级" badge on any panel whose response carries `degraded: true`.
3. THE Admin_Backend SHALL emit a structured warning log for each degraded response containing the failing downstream service name and the underlying error class.
4. THE Admin_Backend SHALL expose a `GET /api/v1/admin/health` endpoint returning per-downstream-service health status (`UP` / `DOWN` / `DEGRADED`) so the dashboard can render a system-health panel.
