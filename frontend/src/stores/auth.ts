import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User } from '../types'
import { login as loginApi, register as registerApi, getProfile, logout as logoutApi } from '../api/auth'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('token') || '')
  const refreshToken = ref(localStorage.getItem('refreshToken') || '')
  const user = ref<User | null>(null)

  const isLoggedIn = computed(() => !!token.value)

  function toUser(profile: Partial<User> & { id: string; username: string; email: string; verified?: boolean }) {
    return {
      id: profile.id,
      username: profile.username,
      email: profile.email,
      avatarUrl: profile.avatarUrl,
      verified: profile.verified ?? false,
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
    // 先告诉后端把 JTI 写黑名单（fail-open：失败不阻塞用户登出）
    if (token.value) {
      try {
        await logoutApi()
      } catch {
        // 静默吞下 — 本地凭据还是要清掉
      }
    }
    token.value = ''
    refreshToken.value = ''
    user.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('refreshToken')
  }

  return { token, refreshToken, user, isLoggedIn, login, register, fetchProfile, logout }
})
