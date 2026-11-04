<template>
  <div ref="rootRef" class="relative">
    <div
      ref="viewportRef"
      class="ui-scroll-viewport overflow-auto"
      :class="viewportClass"
      @scroll.passive="updateMetrics"
    >
      <slot />
    </div>

    <!-- 垂直滚动条：原生滚动条已隐藏，这里是自绘拇指 -->
    <div
      v-show="showY"
      class="pointer-events-none absolute inset-y-1 right-0.5 w-1.5"
      aria-hidden="true"
    >
      <div
        class="ui-scroll-thumb"
        :class="[thumbClass, dragging === 'y' ? 'opacity-100' : '']"
        :style="{ height: `${thumbY.height}px`, transform: `translateY(${thumbY.top}px)` }"
        @pointerdown.prevent="startDrag('y', $event)"
      />
    </div>

    <!-- 水平滚动条 -->
    <div
      v-show="showX"
      class="pointer-events-none absolute inset-x-1 bottom-0.5 h-1.5"
      aria-hidden="true"
    >
      <div
        class="ui-scroll-thumb h-full"
        :class="[thumbClass, dragging === 'x' ? 'opacity-100' : '']"
        :style="{ width: `${thumbX.width}px`, transform: `translateX(${thumbX.left}px)` }"
        @pointerdown.prevent="startDrag('x', $event)"
      />
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';

const props = defineProps({
  // 'y' 仅纵向；'both' 双向
  axis: {
    type: String,
    default: 'y'
  },
  viewportClass: {
    type: String,
    default: ''
  },
  thumbClass: {
    type: String,
    default: 'bg-zinc-400/70 hover:bg-zinc-500/90 dark:bg-zinc-500/60 dark:hover:bg-zinc-400/80'
  }
});

const TRACK_INSET = 4;
const MIN_THUMB = 20;

const rootRef = ref(null);
const viewportRef = ref(null);
const dragging = ref('');

const metrics = reactive({
  scrollTop: 0,
  scrollLeft: 0,
  scrollHeight: 0,
  clientHeight: 0,
  scrollWidth: 0,
  clientWidth: 0
});

const showY = computed(() => metrics.scrollHeight > metrics.clientHeight + 1);
const showX = computed(
  () => props.axis === 'both' && metrics.scrollWidth > metrics.clientWidth + 1
);

function updateMetrics() {
  const el = viewportRef.value;
  if (!el) return;
  metrics.scrollTop = el.scrollTop;
  metrics.scrollLeft = el.scrollLeft;
  metrics.scrollHeight = el.scrollHeight;
  metrics.clientHeight = el.clientHeight;
  metrics.scrollWidth = el.scrollWidth;
  metrics.clientWidth = el.clientWidth;
}

const thumbY = computed(() => {
  const track = Math.max(0, metrics.clientHeight - TRACK_INSET * 2);
  const ratio = metrics.scrollHeight ? metrics.clientHeight / metrics.scrollHeight : 1;
  const height = Math.min(track, Math.max(MIN_THUMB, track * ratio));
  const maxScroll = metrics.scrollHeight - metrics.clientHeight;
  const maxTop = Math.max(0, track - height);
  const top = maxScroll > 0 ? (metrics.scrollTop / maxScroll) * maxTop : 0;
  return { height, top };
});

const thumbX = computed(() => {
  const track = Math.max(0, metrics.clientWidth - TRACK_INSET * 2);
  const ratio = metrics.scrollWidth ? metrics.clientWidth / metrics.scrollWidth : 1;
  const width = Math.min(track, Math.max(MIN_THUMB * 1.6, track * ratio));
  const maxScroll = metrics.scrollWidth - metrics.clientWidth;
  const maxLeft = Math.max(0, track - width);
  const left = maxScroll > 0 ? (metrics.scrollLeft / maxScroll) * maxLeft : 0;
  return { width, left };
});

/* ---------------- 拖拽 ---------------- */

let startCoord = 0;
let startScroll = 0;
let activeAxis = '';

function onDragMove(event) {
  const el = viewportRef.value;
  if (!el || !activeAxis) return;
  if (activeAxis === 'y') {
    const maxScroll = metrics.scrollHeight - metrics.clientHeight;
    const maxTop = Math.max(1, metrics.clientHeight - TRACK_INSET * 2 - thumbY.value.height);
    el.scrollTop = startScroll + ((event.clientY - startCoord) * maxScroll) / maxTop;
  } else {
    const maxScroll = metrics.scrollWidth - metrics.clientWidth;
    const maxLeft = Math.max(1, metrics.clientWidth - TRACK_INSET * 2 - thumbX.value.width);
    el.scrollLeft = startScroll + ((event.clientX - startCoord) * maxScroll) / maxLeft;
  }
}

function endDrag() {
  dragging.value = '';
  activeAxis = '';
  window.removeEventListener('pointermove', onDragMove);
  window.removeEventListener('pointerup', endDrag);
  window.removeEventListener('pointercancel', endDrag);
}

function startDrag(axis, event) {
  const el = viewportRef.value;
  if (!el) return;
  dragging.value = axis;
  activeAxis = axis;
  startCoord = axis === 'y' ? event.clientY : event.clientX;
  startScroll = axis === 'y' ? el.scrollTop : el.scrollLeft;
  window.addEventListener('pointermove', onDragMove);
  window.addEventListener('pointerup', endDrag);
  window.addEventListener('pointercancel', endDrag);
}

/* ---------------- 尺寸同步 ---------------- */

let resizeObserver = null;
let mutationObserver = null;

onMounted(() => {
  updateMetrics();
  const el = viewportRef.value;
  if (!el) return;
  if (typeof ResizeObserver === 'function') {
    resizeObserver = new ResizeObserver(updateMetrics);
    resizeObserver.observe(el);
  }
  // 选项/表格行数变化时高度也会变，需要重新计算拇指长度
  if (typeof MutationObserver === 'function') {
    mutationObserver = new MutationObserver(() => updateMetrics());
    mutationObserver.observe(el, { childList: true, subtree: true });
  }
  window.addEventListener('resize', updateMetrics);
});

onBeforeUnmount(() => {
  endDrag();
  resizeObserver?.disconnect();
  mutationObserver?.disconnect();
  window.removeEventListener('resize', updateMetrics);
});
</script>

<style scoped>
/* 隐藏原生滚动条，改用上面的自绘拇指 */
.ui-scroll-viewport {
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.ui-scroll-viewport::-webkit-scrollbar {
  display: none;
  width: 0;
  height: 0;
}

.ui-scroll-thumb {
  border-radius: 9999px;
  opacity: 0.65;
  transition: opacity 150ms ease, background-color 150ms ease;
  pointer-events: auto;
  cursor: grab;
  touch-action: none;
}

.ui-scroll-thumb:hover,
.ui-scroll-thumb:active {
  opacity: 1;
}

.ui-scroll-thumb:active {
  cursor: grabbing;
}
</style>
