import client from './client'
import type { ApiResponse } from '../types'

export interface AvatarTraits {
  coreColor: string
  auraColor: string
  particleType: 'stars' | 'fireflies' | 'snowflakes' | 'petals' | 'embers' | 'crystals'
  particleCount: number
  coreShape: 'sphere' | 'crystal' | 'nebula' | 'prism' | 'torus'
  auraIntensity: number
  rotationSpeed: number
  glowRadius: number
  trailEffect: boolean
  secondaryColor: string
  pulseFrequency: number
}

export interface EmotionTone {
  warmth: number
  energy: number
  depth: number
  brightness: number
  mystique: number
}

export interface AvatarProfile {
  id: string
  userId: string
  selfDescription: string
  /** JSON 字符串，需 JSON.parse 后得到 AvatarTraits */
  avatarTraits: string
  /** JSON 字符串，需 JSON.parse 后得到 EmotionTone */
  emotionTone: string
  /** JSON 字符串，需 JSON.parse 后得到 string[] */
  personalityTags: string
  avatarTitle: string
  avatarStory: string
  isPublic: boolean
  createdAt: string
  updatedAt: string
}

export interface AvatarProfileRequest {
  selfDescription: string
  isPublic?: boolean
}

/** 获取当前用户的 3D 刻画档案（未创建时 data=null）。 */
export function getMyAvatarProfile() {
  return client.get<ApiResponse<AvatarProfile | null>>('/users/me/avatar-profile')
}

/**
 * 创建或更新当前用户的 3D 刻画档案。
 * AI 生成约需 5-15 秒，使用 30s timeout。
 */
export function createOrUpdateAvatarProfile(data: AvatarProfileRequest) {
  return client.post<ApiResponse<AvatarProfile>>('/users/me/avatar-profile', data, {
    timeout: 30_000,
  })
}

/** 删除当前用户的 3D 刻画档案。 */
export function deleteAvatarProfile() {
  return client.delete<ApiResponse<void>>('/users/me/avatar-profile')
}

/** 获取指定用户的公开 3D 刻画档案（用于共鸣空间）。 */
export function getUserAvatarProfile(userId: string) {
  return client.get<ApiResponse<AvatarProfile | null>>(`/users/${userId}/avatar-profile`)
}

/** 解析 AvatarProfile 中的 JSON 字段，返回强类型对象。 */
export function parseAvatarProfile(profile: AvatarProfile): {
  traits: AvatarTraits
  emotionTone: EmotionTone
  tags: string[]
} {
  const defaultTraits: AvatarTraits = {
    coreColor: '#6c63ff',
    auraColor: '#36d8b4',
    particleType: 'stars',
    particleCount: 1500,
    coreShape: 'sphere',
    auraIntensity: 0.75,
    rotationSpeed: 0.4,
    glowRadius: 2.5,
    trailEffect: true,
    secondaryColor: '#f59e0b',
    pulseFrequency: 1.2,
  }
  const defaultTone: EmotionTone = {
    warmth: 0.5,
    energy: 0.5,
    depth: 0.5,
    brightness: 0.5,
    mystique: 0.5,
  }

  let traits = defaultTraits
  let emotionTone = defaultTone
  let tags: string[] = []

  try {
    if (profile.avatarTraits) traits = { ...defaultTraits, ...JSON.parse(profile.avatarTraits) }
  } catch { /* ignore */ }

  try {
    if (profile.emotionTone) emotionTone = { ...defaultTone, ...JSON.parse(profile.emotionTone) }
  } catch { /* ignore */ }

  try {
    if (profile.personalityTags) tags = JSON.parse(profile.personalityTags)
  } catch { /* ignore */ }

  return { traits, emotionTone, tags }
}
