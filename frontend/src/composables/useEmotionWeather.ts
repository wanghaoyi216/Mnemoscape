import { globalWeatherEffect, type WeatherEffect } from './useWeatherFx'

export interface EmotionVector {
  joy?: number
  sadness?: number
  nostalgia?: number
  excitement?: number
  calm?: number
  melancholy?: number
  gratitude?: number
  anxiety?: number
}

const emotionToWeather: Record<string, WeatherEffect> = {
  joy: 'cherry_blossoms',
  sadness: 'snow',
  nostalgia: 'falling_leaves',
  excitement: 'lightning',
  calm: 'fireflies',
  melancholy: 'snow',
  gratitude: 'cherry_blossoms',
  anxiety: 'lightning',
}

export function useEmotionWeather() {
  function mapEmotionToWeather(emotions: EmotionVector | null | undefined): WeatherEffect {
    if (!emotions) return 'none'

    let dominant = 'calm'
    let maxScore = 0

    for (const [key, value] of Object.entries(emotions)) {
      if (typeof value === 'number' && value > maxScore) {
        maxScore = value
        dominant = key
      }
    }

    if (maxScore < 0.3) return 'none'
    return emotionToWeather[dominant] || 'fireflies'
  }

  function applyEmotion(emotions: EmotionVector | null | undefined) {
    const effect = mapEmotionToWeather(emotions)
    globalWeatherEffect.value = effect
  }

  function clear() {
    globalWeatherEffect.value = 'none'
  }

  return {
    applyEmotion,
    mapEmotionToWeather,
    clear,
  }
}
