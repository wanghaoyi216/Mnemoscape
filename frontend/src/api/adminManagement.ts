/**
 * 管理后台数据表格 API（用户 / 记忆 / 共鸣 / 客服工单）
 *
 * 与 admin.ts 的"只读统计"类不同，这里是真正会做写操作（删除、改隐私、改角色等）
 * 的管理端接口。所有路径都要求 ROLE_ADMIN。
 */
import client from './client'
import type { ApiResponse, PageResult } from '../types'

// ========== Users management ==========

export interface AdminUserRow {
  id: string
  username: string
  email: string
  avatarUrl?: string | null
  role: 'USER' | 'ADMIN'
  verified: boolean
  createdAt?: string | null
  updatedAt?: string | null
}

export interface ListUsersParams {
  page?: number
  size?: number
  search?: string
  role?: 'USER' | 'ADMIN'
  verified?: boolean
  sortBy?: string
  sortDir?: 'asc' | 'desc'
}

export function listUsers(params: ListUsersParams = {}) {
  return client.get<ApiResponse<PageResult<AdminUserRow>>>('/admin/users-management', { params })
}

export function getUser(userId: string) {
  return client.get<ApiResponse<AdminUserRow>>(`/admin/users-management/${userId}`)
}

export function changeUserRole(userId: string, role: 'USER' | 'ADMIN') {
  return client.patch<ApiResponse<AdminUserRow>>(`/admin/users-management/${userId}/role`, { role })
}

export function changeUserVerified(userId: string, verified: boolean) {
  return client.patch<ApiResponse<AdminUserRow>>(`/admin/users-management/${userId}/verified`, { verified })
}

export function deleteUser(userId: string) {
  return client.delete<ApiResponse<void>>(`/admin/users-management/${userId}`)
}

export function batchDeleteUsers(ids: string[]) {
  return client.post<ApiResponse<{ deleted: number; failed: string[] }>>(
    '/admin/users-management/batch-delete', { ids })
}

// ========== Memories management ==========

export interface AdminMemoryRow {
  id: string
  userId: string
  title: string
  description?: string | null
  memoryYear?: number | null
  memoryDate?: string | null
  memorySeason?: string | null
  memoryTimeOfDay?: string | null
  memoryLocation?: string | null
  memoryLng?: number | null
  memoryLat?: number | null
  privacyLevel: 'PRIVATE' | 'FRIENDS' | 'PUBLIC'
  isLocked: boolean
  fadeLevel?: number | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface ListMemoriesParams {
  page?: number
  size?: number
  search?: string
  userId?: string
  privacyLevel?: 'PRIVATE' | 'FRIENDS' | 'PUBLIC'
  locked?: boolean
  sortBy?: string
  sortDir?: 'asc' | 'desc'
}

export function listMemoriesAdmin(params: ListMemoriesParams = {}) {
  return client.get<ApiResponse<PageResult<AdminMemoryRow>>>('/admin/memories', { params })
}

export function patchMemory(id: string, body: { privacyLevel?: string; locked?: boolean; fadeLevel?: number }) {
  return client.patch<ApiResponse<AdminMemoryRow>>(`/admin/memories/${id}`, body)
}

export function deleteMemoryAdmin(id: string) {
  return client.delete<ApiResponse<void>>(`/admin/memories/${id}`)
}

export function batchDeleteMemories(ids: string[]) {
  return client.post<ApiResponse<{ deleted: number; failed: string[] }>>(
    '/admin/memories/batch-delete', { ids })
}

export function batchUpdatePrivacy(ids: string[], privacyLevel: 'PRIVATE' | 'FRIENDS' | 'PUBLIC') {
  return client.post<ApiResponse<{ updated: number; privacyLevel: string }>>(
    '/admin/memories/batch-privacy', { ids, privacyLevel })
}

export function batchLockMemories(ids: string[], locked: boolean) {
  return client.post<ApiResponse<{ updated: number; locked: boolean }>>(
    '/admin/memories/batch-lock', { ids, locked })
}

// ========== Maintenance tools（向量回填 / visualData 清洗 / MinIO 历史迁移）==========

export interface VectorBackfillResult {
  total: number
  dispatched: number
  limit: number
}

/**
 * 维护工具批量操作的超时上限。这些操作会遍历成百上千条记忆（geocoding 还会打
 * 外部 Nominatim API），远超默认的 15s axios 超时 —— 不放宽会必现 ECONNABORTED
 * 让前端误报"操作失败"。给 5 分钟上限。
 */
const MAINTENANCE_TIMEOUT = 300_000

/** 把现有记忆批量重新索引进 Milvus（首次接入向量检索后给历史数据补索引）。 */
export function backfillVectors(limit = 500) {
  return client.post<ApiResponse<VectorBackfillResult>>(
    '/admin/memories/backfill-vectors', { limit }, { timeout: MAINTENANCE_TIMEOUT })
}

export interface VisualDataCleanupResult {
  scanned: number
  dispatched: number
  limit: number
  total: number
}

/** 批量重建 visualData 为 null/空 或仍是旧英文模板的记忆。 */
export function cleanupVisualData(limit = 500) {
  return client.post<ApiResponse<VisualDataCleanupResult>>(
    '/admin/memories/cleanup-visualdata', { limit }, { timeout: MAINTENANCE_TIMEOUT })
}

export interface GeoBackfillResult {
  scanned: number
  resolved: number
  skipped: number
  limit: number
  total: number
}

/** 给所有 memoryLocation 非空但坐标为 null 的历史记忆补填经纬度。 */
export function backfillGeocoords(limit = 1000) {
  return client.post<ApiResponse<GeoBackfillResult>>(
    '/admin/memories/backfill-geocoords', { limit }, { timeout: MAINTENANCE_TIMEOUT })
}

export interface OrphanMigrationResult {
  scanned: number
  candidates: number
  migrated: number
  dryRun: boolean
  samples: string[]
  error?: string
}

/** 扫描 / 迁移 MinIO 历史无前缀孤儿对象到 legacy-orphan/。apply=false 为预览。 */
export function migrateLegacyOrphans(apply = false) {
  return client.post<ApiResponse<OrphanMigrationResult>>(
    `/admin/assets/migrate-legacy-orphans?apply=${apply}`)
}

// ========== Resonance management ==========

export interface AdminResonanceRow {
  id: string
  memoryAId: string
  memoryBId: string
  resonanceScore: number
  emotionSimilarity?: number | null
  sceneSimilarity?: number | null
  status: string
  createdAt?: string | null
}

export interface ListResonancesParams {
  page?: number
  size?: number
  status?: string
  sortBy?: string
  sortDir?: 'asc' | 'desc'
}

export function listResonancesAdmin(params: ListResonancesParams = {}) {
  return client.get<ApiResponse<PageResult<AdminResonanceRow>>>('/admin/resonance-management', { params })
}

export function patchResonance(id: string, body: { status?: string }) {
  return client.patch<ApiResponse<AdminResonanceRow>>(`/admin/resonance-management/${id}`, body)
}

export function deleteResonance(id: string) {
  return client.delete<ApiResponse<void>>(`/admin/resonance-management/${id}`)
}

export function batchDeleteResonances(ids: string[]) {
  return client.post<ApiResponse<{ deleted: number; failed: string[] }>>(
    '/admin/resonance-management/batch-delete', { ids })
}

export function batchUpdateResonanceStatus(ids: string[], status: string) {
  return client.post<ApiResponse<{ updated: number; status: string }>>(
    '/admin/resonance-management/batch-status', { ids, status })
}

// ========== Support tickets (admin side) ==========

export interface SupportTicket {
  id: string
  userId: string
  subject: string
  description?: string | null
  status: 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED'
  priority: 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
  assignedAdminId?: string | null
  clientType?: string | null
  lastMessageAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface SupportMessage {
  id: string
  ticketId: string
  senderId: string
  senderRole: 'USER' | 'ADMIN' | 'SYSTEM'
  content: string
  messageType: 'TEXT' | 'IMAGE' | 'FILE' | 'EMOJI' | 'SYSTEM'
  fileName?: string | null
  fileSize?: number | null
  readAt?: string | null
  createdAt?: string | null
}

export interface ListSupportTicketsParams {
  page?: number
  size?: number
  status?: string
  priority?: string
  userId?: string
  search?: string
  assignedAdminId?: string
}

export function listSupportTickets(params: ListSupportTicketsParams = {}) {
  return client.get<ApiResponse<PageResult<SupportTicket>>>('/admin/support/tickets', { params })
}

export function getSupportStats() {
  return client.get<ApiResponse<Record<string, number>>>('/admin/support/stats')
}

export function patchSupportTicket(ticketId: string, body: {
  status?: string
  priority?: string
  assignedAdminId?: string | null
}) {
  return client.patch<ApiResponse<SupportTicket>>(`/admin/support/tickets/${ticketId}`, body)
}

export function deleteSupportTicket(ticketId: string) {
  return client.delete<ApiResponse<void>>(`/admin/support/tickets/${ticketId}`)
}

export function batchUpdateTicketStatus(ids: string[], status: string) {
  return client.post<ApiResponse<{ updated: number }>>(
    '/admin/support/tickets/batch-status', { ids, status })
}

// Admin uses the same /api/v1/support/tickets/{id}/messages endpoint as users
// but with X-User-Role=ADMIN — gateway enforces; backend permissive for ADMIN.
export function listTicketMessages(ticketId: string) {
  return client.get<ApiResponse<SupportMessage[]>>(`/support/tickets/${ticketId}/messages`)
}

export function sendTicketMessage(ticketId: string, body: {
  content: string
  messageType?: string
  fileName?: string
  fileSize?: number
}) {
  return client.post<ApiResponse<SupportMessage>>(
    `/support/tickets/${ticketId}/messages`, body)
}
