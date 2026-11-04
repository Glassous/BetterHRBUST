<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { toPng } from 'html-to-image'
import { posters } from './posters/registry'

// 导出固定 2 倍图（1200 × 1600 → 2400 × 3200）
const PIXEL_RATIO = 2
const iconUrl = `${import.meta.env.BASE_URL}BetterHRBUST.png`

const activeId = ref(posters[0].id)
const current = computed(() => posters.find((item) => item.id === activeId.value) ?? posters[0])

const posterEl = ref<HTMLDivElement | null>(null)
const scale = ref(0.5)
const exporting = ref(false)
const toast = ref('')

let toastTimer: ReturnType<typeof setTimeout> | undefined

// 预览按「适应窗口」缩放：只缩放外层包裹元素，海报节点本身保持原始像素尺寸，
// 这样导出时不会把 CSS transform 一起栅格化进图片。
function fit() {
  const availW = Math.min(window.innerWidth - 48, 1160)
  const availH = window.innerHeight - 64 - 170
  scale.value = Math.max(0.12, Math.min(availW / current.value.width, availH / current.value.height, 1))
}

function showToast(text: string) {
  toast.value = text
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toast.value = ''), 3200)
}

async function exportPng() {
  const node = posterEl.value
  if (!node || exporting.value) return
  const { width, height, fileName } = current.value
  exporting.value = true
  try {
    // 等字体就绪，避免首帧导出时字体回退导致的文案错位
    await document.fonts.ready
    const dataUrl = await toPng(node, {
      width,
      height,
      pixelRatio: PIXEL_RATIO,
      backgroundColor: '#eef3ff',
      cacheBust: true,
      style: { transform: 'none', transformOrigin: 'top left' }
    })
    const link = document.createElement('a')
    link.download = fileName
    link.href = dataUrl
    link.click()
    showToast(`已导出 ${width * PIXEL_RATIO} × ${height * PIXEL_RATIO} PNG`)
  } catch (error) {
    showToast(`导出失败：${error instanceof Error ? error.message : String(error)}`)
  } finally {
    exporting.value = false
  }
}

function selectPoster(id: string) {
  activeId.value = id
  fit()
}

onMounted(() => {
  fit()
  window.addEventListener('resize', fit)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', fit)
  clearTimeout(toastTimer)
})
</script>

<template>
  <div class="min-h-screen bg-[#eef2f9]">
    <div
      class="pointer-events-none fixed inset-0 bg-[radial-gradient(60%_45%_at_50%_0%,rgba(59,130,246,0.16),rgba(59,130,246,0))]"
    />

    <!-- 顶部工具条 -->
    <header class="sticky top-0 z-20 border-b border-black/[0.06] bg-white/80 backdrop-blur-xl">
      <div class="mx-auto flex h-16 max-w-[1400px] items-center justify-between gap-4 px-5 sm:px-8">
        <div class="flex min-w-0 items-center gap-3">
          <img :src="iconUrl" alt="BetterHRBUST" class="size-9 object-contain" />
          <div class="min-w-0">
            <div class="truncate text-[15px] font-bold tracking-tight">BetterHRBUST 海报生成器</div>
            <div class="truncate text-[11.5px] font-medium text-[#6b7a92]">{{ current.meta }} · {{ current.name }}</div>
          </div>
        </div>

        <div class="flex shrink-0 items-center gap-3">
          <span
            class="hidden rounded-full bg-[#f2f5fa] px-3 py-1.5 text-[11.5px] font-semibold text-[#5b6b84] ring-1 ring-inset ring-black/[0.04] sm:inline"
            >{{ current.width }} × {{ current.height }}</span
          >
          <button
            type="button"
            class="inline-flex items-center gap-2 rounded-full bg-[#1b64e0] px-4 py-2 text-[13px] font-semibold text-white shadow-[0_10px_22px_-12px_rgba(27,100,224,0.9)] transition hover:bg-[#0f56cd] active:translate-y-px disabled:cursor-not-allowed disabled:opacity-60"
            :disabled="exporting"
            @click="exportPng"
          >
            <svg v-if="!exporting" class="size-4" viewBox="0 0 20 20" fill="none">
              <path
                d="M10 3v9m0 0 3.2-3.2M10 12 6.8 8.8M4 15.2V16a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1v-.8"
                stroke="currentColor"
                stroke-width="1.7"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
            <span v-else class="size-4 animate-spin rounded-full border-2 border-white/40 border-t-white" />
            {{ exporting ? '导出中…' : '下载 PNG' }}
          </button>
        </div>
      </div>
    </header>

    <!-- 海报预览 -->
    <main class="relative mx-auto flex max-w-[1400px] flex-col items-center px-5 pb-10 pt-7 sm:px-8">
      <div v-if="posters.length > 1" class="mb-5 flex flex-wrap items-center justify-center gap-2">
        <button
          v-for="item in posters"
          :key="item.id"
          type="button"
          class="rounded-full px-3.5 py-1.5 text-[12.5px] font-semibold transition"
          :class="
            item.id === activeId
              ? 'bg-[#0b1526] text-white shadow-[0_10px_20px_-14px_rgba(11,21,38,0.9)]'
              : 'bg-white/80 text-[#5b6b84] ring-1 ring-inset ring-black/[0.06] hover:text-[#0b1526]'
          "
          @click="selectPoster(item.id)"
        >
          {{ item.name }}
        </button>
      </div>

      <div
        class="relative overflow-hidden rounded-[10px] shadow-[0_40px_80px_-40px_rgba(15,23,42,0.45),0_2px_8px_rgba(15,23,42,0.06)] ring-1 ring-black/[0.06]"
        :style="{ width: `${current.width * scale}px`, height: `${current.height * scale}px` }"
      >
        <div :style="{ transform: `scale(${scale})`, transformOrigin: 'top left' }">
          <div ref="posterEl" :style="{ width: `${current.width}px`, height: `${current.height}px` }">
            <component :is="current.component" />
          </div>
        </div>
      </div>

      <p class="mt-5 text-center text-[12px] font-medium text-[#7a89a1]">
        海报内容为硬编码，改文案请编辑对应海报组件 · 导出为 2 倍图
        {{ current.width * PIXEL_RATIO }} × {{ current.height * PIXEL_RATIO }}
      </p>
    </main>

    <!-- 导出结果提示 -->
    <Transition
      enter-active-class="transition duration-200"
      enter-from-class="translate-y-2 opacity-0"
      leave-active-class="transition duration-200"
      leave-to-class="translate-y-2 opacity-0"
    >
      <div
        v-if="toast"
        class="fixed bottom-8 left-1/2 z-30 -translate-x-1/2 rounded-full bg-[#0b1526] px-5 py-2.5 text-[12.5px] font-semibold text-white shadow-[0_20px_40px_-20px_rgba(11,21,38,0.8)]"
      >
        {{ toast }}
      </div>
    </Transition>
  </div>
</template>
