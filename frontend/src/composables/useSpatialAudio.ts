import { Howl } from 'howler'
import { ref, onUnmounted } from 'vue'

export function useSpatialAudio() {
  const isPlaying = ref(false)
  let ambient: Howl | null = null
  const positionals: Howl[] = []

  function loadAmbient(src: string, volume = 0.3, loop = true) {
    ambient = new Howl({ src, volume, loop, html5: true })
  }

  function addPositional(src: string, _pos: { x: number; y: number; z: number }, volume = 0.5) {
    const snd = new Howl({
      src,
      volume,
      loop: false,
      html5: true,
    })
    positionals.push(snd)
    return snd
  }

  function playAmbient() {
    ambient?.play()
    isPlaying.value = true
  }

  function setReverb(amount: number) {
    // Howler doesn't have native reverb — mock via volume reduction
    ambient?.volume(Math.max(0, 0.3 - amount * 0.25))
  }

  function stopAll() {
    ambient?.stop()
    positionals.forEach((s) => s.stop())
    isPlaying.value = false
  }

  onUnmounted(stopAll)

  return { isPlaying, loadAmbient, addPositional, playAmbient, setReverb, stopAll }
}
