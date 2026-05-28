/**
 * Admin dashboard API client (R13.1, R13.3, R13.4).
 *
 * 9 个只读聚合端点的 typed fetch 封装。所有函数都走共享 `client` axios 实例：
 *   - request interceptor 自动注入 `Authorization: Bearer <token>` 与 `Accept-Language`
 *   - response 统一是后端的 `ApiResponse<T>` 外壳
 *
 * 响应 TypeScript 类型严格对应后端 `record` 结构（design.md §Privacy Enforcement
 * DTO 白名单与 §Backend Components 中的 record 签名）。任何敏感字段（title /
 * description / email / passwordHash 等）禁止出现在这些类型上。
 *
 * 降级响应（R18.1）：当后端拼装多数据源失败时，`data` 字段除了正常 payload
 * 还会带 `degraded: true` + `degradedReasons: string[]`。我们用泛型 `Degradable<T>`
 * 表达这种"原 payload + 顶层降级标记"的结构，由 `useAdminApi` 在 composable 层
 * 解包扁平化到 panel 状态。
 */
import client from './client'
import type { ApiResponse } from '../types'

// ---------- 通用类型 ----------

/**
 * 后端 admin 端点在依赖失败时仍返回 200 + `data` 上挂 `degraded` 标记（R18.1）。
 * 当前协议中只有 `top-contributors` 实际会触发降级（auth-service username 查找失败），
 * 但前端契约对所有端点保持兼容：未降级时 `degraded` / `degradedReasons` 缺失即 false。
 */
export interface DegradableEnvelope {
  degraded?: boolean
  degradedReasons?: string[]
}

/** 时间维度（与后端 `Dimension` enum 对齐，R6.6）。 */
export type AdminDimension = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY'

/** 热力网格分辨率（R9.3）。 */
export type AdminGridResolution = 'LOW' | 'MEDIUM' | 'HIGH'

/** 单组件健康状态（R18.4）。 */
export type AdminHealthStatus = 'UP' | 'DEGRADED' | 'DOWN'

// ---------- 端点 1：active-users (R6) ----------

/** `record ActiveUserBucket(String bucket, long activeUserCount)`. */
export interface ActiveUserBucket {
  bucket: string
  activeUserCount: number
}

export interface GetActiveUsersParams {
  dimension: AdminDimension
  /** ISO 8601 calendar date `YYYY-MM-DD`；省略时由后端按维度填默认窗口。 */
  from?: string
  /** ISO 8601 calendar date `YYYY-MM-DD`；省略时由后端按维度填默认窗口。 */
  to?: string
}

export function getActiveUsers(params: GetActiveUsersParams) {
  return client.get<ApiResponse<ActiveUserBucket[]>>('/admin/stats/active-users', { params })
}

// ---------- 端点 2：memory-trends (R7) ----------

/** `record MemoryTrendBucket(String bucket, long createdCount, long modifiedCount)`. */
export interface MemoryTrendBucket {
  bucket: string
  createdCount: number
  modifiedCount: number
}

export interface GetMemoryTrendsParams {
  dimension: AdminDimension
  from?: string
  to?: string
}

export function getMemoryTrends(params: GetMemoryTrendsParams) {
  return client.get<ApiResponse<MemoryTrendBucket[]>>('/admin/stats/memory-trends', { params })
}

// ---------- 端点 3：emotion-distribution (R8) ----------

/**
 * `record EmotionDistribution(double joy, double sadness, double anger, double fear,
 *                              double surprise, double nostalgia, double peace,
 *                              double melancholy, long sampleSize)`.
 * 当 sampleSize=0 时所有 8 个分量为 0.0（R8.3）。
 */
export interface EmotionDistribution {
  joy: number
  sadness: number
  anger: number
  fear: number
  surprise: number
  nostalgia: number
  peace: number
  melancholy: number
  sampleSize: number
}

export interface GetEmotionDistributionParams {
  from?: string
  to?: string
}

export function getEmotionDistribution(params: GetEmotionDistributionParams = {}) {
  return client.get<ApiResponse<EmotionDistribution>>('/admin/stats/emotion-distribution', { params })
}

// ---------- 端点 4：heatmap (R9) ----------

/** `record HeatmapPoint(double lat, double lon, double intensity)`. */
export interface HeatmapPoint {
  lat: number
  lon: number
  intensity: number
}

export interface GetHeatmapParams {
  gridResolution: AdminGridResolution
}

export function getHeatmap(params: GetHeatmapParams) {
  return client.get<ApiResponse<HeatmapPoint[]>>('/admin/stats/heatmap', { params })
}

// ---------- 端点 5：top-contributors (R10) ----------

/**
 * `record TopContributor(String userId, String username, long memoryCount)`.
 * **白名单**：除这三字段外没有 email / passwordHash / avatarUrl 等（R10.4 / R15.2）。
 */
export interface TopContributor {
  userId: string
  username: string
  memoryCount: number
}

export interface GetTopContributorsParams {
  /** 默认 10，capped 100；非正抛 400 `INVALID_LIMIT`. */
  limit?: number
  from?: string
  to?: string
}

/**
 * Top contributors 响应在降级时（auth-service username 查找失败）`data` 顶层会
 * 携带 `degraded: true` + `degradedReasons`，items 中每条 username 退化为
 * `userId.substring(0,8)`。
 */
export interface TopContributorsResponse extends DegradableEnvelope {
  items: TopContributor[]
}

export function getTopContributors(params: GetTopContributorsParams = {}) {
  return client.get<ApiResponse<TopContributorsResponse>>('/admin/stats/top-contributors', { params })
}

// ---------- 端点 6：fragment-discovery (R11) ----------

/** `record FragmentDiscoveryOverall(long totalFragments, long discoveredFragments, double discoveryRate)`. */
export interface FragmentDiscoveryOverall {
  totalFragments: number
  discoveredFragments: number
  discoveryRate: number
}

/**
 * `record FragmentDiscoveryByType(String fragmentType, long totalFragments,
 *                                 long discoveredFragments, double discoveryRate)`.
 * `fragmentType` ∈ {`forgotten_detail`, `emotion_flashback`, `linked_door`}.
 */
export interface FragmentDiscoveryByType {
  fragmentType: string
  totalFragments: number
  discoveredFragments: number
  discoveryRate: number
}

export interface GetFragmentDiscoveryParams {
  /** 缺省返回 overall；传 `'fragmentType'` 时返回 list。 */
  groupBy?: 'fragmentType'
}

/**
 * 单签名：返回类型是 overall + byType 的并集。
 * 调用方按是否传 `groupBy` 自行判断 / 类型断言取需要的形状；
 * 实际项目里有两个独立 composable（`useAdminFragmentsOverall` /
 * `useAdminFragmentsByType`）已经做了类型聚焦，所以不再用 TypeScript
 * overload 折腾轴线 — overload 签名要与实现签名相容，axios 的
 * `AxiosResponse<T>` 让两种返回 shape 不能直接被 overload 表达。
 */
export function getFragmentDiscovery(params?: GetFragmentDiscoveryParams) {
  return client.get<ApiResponse<FragmentDiscoveryOverall | FragmentDiscoveryByType[]>>(
    '/admin/stats/fragment-discovery',
    { params: params ?? {} },
  )
}

// ---------- 端点 7：resonance-overview (R12) ----------

/** `record ResonanceOverview(long totalEdges, double averageScore, Map<String, Long> statusBreakdown)`. */
export interface ResonanceOverview {
  totalEdges: number
  averageScore: number
  /** key 为 `resonance_spaces.status` 字符串值（如 `pending` / `accepted` / `rejected`）。 */
  statusBreakdown: Record<string, number>
}

export function getResonanceOverview() {
  return client.get<ApiResponse<ResonanceOverview>>('/admin/stats/resonance-overview')
}

// ---------- 端点 8：resonance-top (R12) ----------

/**
 * `record ResonanceTopEdge(String memoryAId, String memoryBId, double resonanceScore,
 *                           String status, OffsetDateTime createdAt)`.
 * **白名单**：仅这 5 个字段，**不**含记忆标题 / 描述 / 用户身份（R12.3 / R15.1）。
 */
export interface ResonanceTopEdge {
  memoryAId: string
  memoryBId: string
  resonanceScore: number
  status: string
  /** ISO 8601 OffsetDateTime 字符串（Jackson 默认序列化）。 */
  createdAt: string
}

export interface GetResonanceTopParams {
  /** 默认 20。 */
  limit?: number
}

export function getResonanceTop(params: GetResonanceTopParams = {}) {
  return client.get<ApiResponse<ResonanceTopEdge[]>>('/admin/stats/resonance-top', { params })
}

// ---------- 端点 9：health (R18.4) ----------

export interface AdminHealthComponent {
  status: AdminHealthStatus
  latencyMs: number
  /** 仅在 DEGRADED / DOWN 时出现。 */
  reason?: string
}

export interface AdminHealth {
  overall: AdminHealthStatus
  /** key 为下游组件名，如 `auth-service` / `memory-service` / `redis` 等。 */
  components: Record<string, AdminHealthComponent>
}

export function getAdminHealth() {
  return client.get<ApiResponse<AdminHealth>>('/admin/health')
}
