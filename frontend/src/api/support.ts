/**
 * 用户端客服工单 API。
 *
 * 工单 / 消息端点（用户与管理员共用同一组路径，由 X-User-Role 控制权限）。
 */
import client from './client'
import type { ApiResponse, PageResult } from '../types'
import type { SupportMessage, SupportTicket } from './adminManagement'

export type { SupportMessage, SupportTicket } from './adminManagement'

export interface CreateTicketBody {
  subject: string
  description?: string
  priority?: 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
  clientType?: string
}

export function createTicket(body: CreateTicketBody) {
  return client.post<ApiResponse<SupportTicket>>('/support/tickets', body)
}

export function listMyTickets(params: { page?: number; size?: number; status?: string } = {}) {
  return client.get<ApiResponse<PageResult<SupportTicket>>>('/support/tickets', { params })
}

export function getTicket(ticketId: string) {
  return client.get<ApiResponse<SupportTicket>>(`/support/tickets/${ticketId}`)
}

export function listMessages(ticketId: string) {
  return client.get<ApiResponse<SupportMessage[]>>(`/support/tickets/${ticketId}/messages`)
}

export function sendMessage(ticketId: string, body: {
  content: string
  messageType?: string
  fileName?: string
  fileSize?: number
}) {
  return client.post<ApiResponse<SupportMessage>>(
    `/support/tickets/${ticketId}/messages`, body)
}

export function closeTicket(ticketId: string) {
  return client.post<ApiResponse<SupportTicket>>(`/support/tickets/${ticketId}/close`, {})
}

export function markRead(ticketId: string) {
  return client.post<ApiResponse<{ updated: number }>>(`/support/tickets/${ticketId}/read`, {})
}
