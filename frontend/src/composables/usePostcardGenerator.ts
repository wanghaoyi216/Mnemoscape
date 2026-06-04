import { ref } from 'vue'
import type { Ref } from 'vue'
import * as THREE from 'three'

export type PostcardStyle = 'vintage' | 'watercolor' | 'minimal' | 'neon'

interface PostcardOptions {
  title: string
  quote: string
  location?: string
  year?: number
  style: PostcardStyle
}

export function usePostcardGenerator(rendererRef: Ref<THREE.WebGLRenderer | null>) {
  const generating = ref(false)

  const styleConfigs: Record<PostcardStyle, {
    bgGradient: [string, string]
    textColor: string
    accentColor: string
    fontFamily: string
    borderStyle: string
  }> = {
    vintage: {
      bgGradient: ['#2c1810', '#4a2c17'],
      textColor: '#f5e6d3',
      accentColor: '#d4a574',
      fontFamily: 'Georgia, serif',
      borderStyle: 'double',
    },
    watercolor: {
      bgGradient: ['#1a2332', '#2d3748'],
      textColor: '#e2e8f0',
      accentColor: '#90cdf4',
      fontFamily: 'system-ui, sans-serif',
      borderStyle: 'solid',
    },
    minimal: {
      bgGradient: ['#0a0a0a', '#1a1a2e'],
      textColor: '#ffffff',
      accentColor: '#6c63ff',
      fontFamily: 'ui-monospace, monospace',
      borderStyle: 'none',
    },
    neon: {
      bgGradient: ['#0f0c29', '#302b63'],
      textColor: '#ffffff',
      accentColor: '#00ff88',
      fontFamily: 'system-ui, sans-serif',
      borderStyle: 'solid',
    },
  }

  function captureScene(): string | null {
    if (!rendererRef.value) return null
    rendererRef.value.render(
      rendererRef.value.domElement.parentElement as any,
      rendererRef.value.domElement as any
    )
    return rendererRef.value.domElement.toDataURL('image/png')
  }

  async function generate(options: PostcardOptions): Promise<string> {
    generating.value = true
    try {
      const width = 1200
      const height = 675
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const ctx = canvas.getContext('2d')!

      const config = styleConfigs[options.style]

      const gradient = ctx.createLinearGradient(0, 0, width, height)
      gradient.addColorStop(0, config.bgGradient[0])
      gradient.addColorStop(1, config.bgGradient[1])
      ctx.fillStyle = gradient
      ctx.fillRect(0, 0, width, height)

      const sceneDataUrl = captureScene()
      if (sceneDataUrl) {
        const img = await loadImage(sceneDataUrl)
        ctx.globalAlpha = 0.4
        ctx.drawImage(img, 0, 0, width, height)
        ctx.globalAlpha = 1.0
      }

      const overlayGradient = ctx.createLinearGradient(0, 0, 0, height)
      overlayGradient.addColorStop(0, 'rgba(0,0,0,0.3)')
      overlayGradient.addColorStop(0.5, 'rgba(0,0,0,0.1)')
      overlayGradient.addColorStop(1, 'rgba(0,0,0,0.6)')
      ctx.fillStyle = overlayGradient
      ctx.fillRect(0, 0, width, height)

      if (config.borderStyle !== 'none') {
        ctx.strokeStyle = config.accentColor
        ctx.lineWidth = config.borderStyle === 'double' ? 3 : 1
        const inset = 24
        ctx.strokeRect(inset, inset, width - inset * 2, height - inset * 2)
        if (config.borderStyle === 'double') {
          ctx.lineWidth = 1
          ctx.strokeRect(inset + 6, inset + 6, width - (inset + 6) * 2, height - (inset + 6) * 2)
        }
      }

      ctx.fillStyle = config.textColor
      ctx.font = `bold 36px ${config.fontFamily}`
      ctx.textAlign = 'center'
      ctx.fillText(options.title, width / 2, height * 0.35)

      ctx.fillStyle = config.accentColor
      ctx.font = `italic 20px ${config.fontFamily}`
      const lines = wrapText(ctx, `"${options.quote}"`, width * 0.7)
      let y = height * 0.48
      for (const line of lines) {
        ctx.fillText(line, width / 2, y)
        y += 28
      }

      ctx.fillStyle = config.textColor
      ctx.globalAlpha = 0.6
      ctx.font = `14px ${config.fontFamily}`
      const meta = [options.location, options.year?.toString()].filter(Boolean).join(' · ')
      if (meta) {
        ctx.fillText(meta, width / 2, height - 50)
      }
      ctx.fillText('Mnemoscape · 忆境星空', width / 2, height - 30)
      ctx.globalAlpha = 1.0

      return canvas.toDataURL('image/jpeg', 0.92)
    } finally {
      generating.value = false
    }
  }

  function download(dataUrl: string, filename = 'memory-postcard.jpg') {
    const a = document.createElement('a')
    a.href = dataUrl
    a.download = filename
    a.click()
  }

  return { generate, download, generating }
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = reject
    img.src = src
  })
}

function wrapText(ctx: CanvasRenderingContext2D, text: string, maxWidth: number): string[] {
  const words = text.split('')
  const lines: string[] = []
  let current = ''

  for (const char of words) {
    const test = current + char
    if (ctx.measureText(test).width > maxWidth) {
      lines.push(current)
      current = char
    } else {
      current = test
    }
  }
  if (current) lines.push(current)
  return lines.slice(0, 4)
}
