import { ref } from 'vue'

export function useDriftEffect(fadeLevel: () => number) {
  const fogDensity = ref(0)
  const saturation = ref(1)
  const audioReverb = ref(0)

  function update() {
    const fl = fadeLevel()
    fogDensity.value = fl * 0.5
    saturation.value = 1 - fl * 0.7
    audioReverb.value = fl * 0.5
  }

  return { fogDensity, saturation, audioReverb, update }
}
