import client from './client'
import type { ApiResponse, AuthResponse, User } from '../types'

export function login(username: string, password: string) {
  return client.post<ApiResponse<AuthResponse>>('/auth/login', { username, password })
}

export function register(data: { username: string; email: string; password: string }) {
  return client.post<ApiResponse<AuthResponse>>('/auth/register', data)
}

export function refreshToken(refreshToken: string) {
  return client.post<ApiResponse<AuthResponse>>(
    '/auth/refresh',
    undefined,
    { headers: { Authorization: `Bearer ${refreshToken}` } },
  )
}

export function getProfile() {
  return client.get<ApiResponse<User>>('/auth/profile')
}

/**
 * 通知后端把当前 access token 的 JTI 写入 Redis 黑名单。
 * 即便接口失败（Redis 抖 / 网络问题），前端依然要把本地凭据清空 — 见 auth store。
 */
export function logout() {
  return client.post<ApiResponse<void>>('/auth/logout')
}
