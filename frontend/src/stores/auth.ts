import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User } from '../types'
import { login as loginApi, register as registerApi, getProfile, logout as logoutApi } from '../api/auth'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('token') || '')
  const refreshToken = ref(localStorage.getItem('refreshToken') || '')
  const user = ref<User | null>(null)

  const isLoggedIn = computed(() => !!token.value)
  // dev 后门：localStorage 设 'forceAdmin=1' 可绕过 isAdmin 判定，强制显示管理入口。
  // 仅用于权限/数据不规范导致入口消失时的排查，生产构建下 import.meta.env.DEV 为 false 自动失效。
  const forceAdmin = computed(() => import.meta.env.DEV && localStorage.getItem('forceAdmin') === '1')
  const isAdmin = computed(() => user.value?.role === 'ADMIN' || forceAdmin.value)

  function toUser(profile: Partial<User> & { id: string; username: string; email: string; verified?: boolean; role?: string }): User {
    return {
      id: profile.id,
      username: profile.username,
      email: profile.email,
      avatarUrl: profile.avatarUrl,
      verified: profile.verified ?? false,
      // 兜底：后端缺失或非 'ADMIN' 时统一视作 'USER'
      role: profile.role === 'ADMIN' ? 'ADMIN' : 'USER',
      createdAt: profile.createdAt,
      updatedAt: profile.updatedAt,
    }
  }

  async function login(username: string, password: string) {
    const { data } = await loginApi(username, password)
    token.value = data.data.accessToken
    refreshToken.value = data.data.refreshToken
    localStorage.setItem('token', token.value)
    localStorage.setItem('refreshToken', refreshToken.value)

    try {
      const profile = await getProfile()
      user.value = toUser(profile.data.data)
    } catch {
      user.value = {
        id: data.data.userId,
        username: data.data.username,
        email: '',
        verified: false,
        role: data.data.role === 'ADMIN' ? 'ADMIN' : 'USER',
      }
    }
  }

  async function register(username: string, email: string, password: string) {
    await registerApi({ username, email, password })
  }

  async function fetchProfile() {
    try {
      const { data } = await getProfile()
      user.value = toUser(data.data)
    } catch {
      logout()
    }
  }

  async function logout() {
    const tempToken = token.value
    // 立即清空本地凭据（Fail-safe：防 401 响应拦截循环）
    token.value = ''
    refreshToken.value = ''
    user.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('refreshToken')

    // 告诉后端把 JTI 写黑名单（fail-open：即使失败或 401 也不阻塞本地登出）
    if (tempToken) {
      try {
        await logoutApi()
      } catch {
        // 静默吞下
      }
    }
  }

  return { token, refreshToken, user, isLoggedIn, isAdmin, login, register, fetchProfile, logout }
})
