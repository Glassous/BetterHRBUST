<template>
  <div class="w-full">
    <!-- 阶段名放在色带外面，按周对齐；窄屏空间不够，交给下面的阶段明细 -->
    <div v-if="showLabels" class="mb-1 hidden grid sm:grid" :style="labelGridStyle">
      <div
        v-for="(segment, index) in labelSegments"
        :key="`label-${segment.key}-${segment.startWeek}-${index}`"
        :style="labelColumnStyle(segment)"
        class="min-w-0 truncate pr-1 text-[11px] leading-4 text-[var(--ink-secondary)]"
        :title="segment.label"
      >
        {{ segment.short }}
      </div>
    </div>

    <div :class="['relative w-full overflow-hidden rounded-[6px] bg-[var(--bg-subtle)]', barClass]">
      <!-- 阶段色块：宽度按周数比例，斜杠周的前后半周也能对上 -->
      <div
        v-for="(segment, index) in segments"
        :key="`${segment.key}-${segment.startWeek}-${index}`"
        :class="['absolute inset-y-0', blockClass(segment)]"
        :style="blockStyle(segment)"
        :title="titleOf(segment)"
      ></div>

      <!-- 每周分隔线：让“还剩几周”可以数出来 -->
      <div class="pointer-events-none absolute inset-0 opacity-60" :style="weekGridStyle" aria-hidden="true"></div>

      <!-- 当前周：整列淡色高亮 + 一条竖线 -->
      <div
        v-if="currentWeek"
        class="pointer-events-none absolute inset-y-0 bg-[var(--accent)]/20"
        :style="currentColumnStyle"
        aria-hidden="true"
      >
        <span class="absolute inset-y-0 left-0 w-[2px] bg-[var(--accent)]"></span>
        <span class="absolute inset-y-0 right-0 w-[1px] bg-[var(--accent)]/50"></span>
      </div>
    </div>

    <div
      v-if="!compact"
      class="mt-2 flex items-center justify-between text-[11px] text-[var(--ink-muted)] num-tabular"
    >
      <span>{{ firstLabel }}</span>
      <span v-if="currentWeek" class="hidden font-medium text-[var(--accent)] sm:inline">当前第 {{ currentWeek }} / {{ totalWeeks }} 周</span>
      <span>{{ lastLabel }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { formatWeekRange } from '@/utils/teachingProcess.js';
import { stageClass, stageStyle } from '@/utils/teachingProcessSymbols.js';

const props = defineProps({
  segments: { type: Array, default: () => [] },
  weekCount: { type: Number, default: 0 },
  currentWeek: { type: Number, default: 0 },
  compact: { type: Boolean, default: false },
  /** 在色带上方按周对齐列出阶段名（文字不压在色块里） */
  showLabels: { type: Boolean, default: false },
  firstDate: { type: String, default: '' },
  lastDate: { type: String, default: '' }
});

const totalWeeks = computed(() => props.weekCount || 1);
const barClass = computed(() => (props.compact ? 'h-2.5' : 'h-5'));

const firstLabel = computed(() => (props.firstDate ? `第 1 周 ${props.firstDate}` : '第 1 周'));
const lastLabel = computed(() =>
  props.lastDate ? `第 ${totalWeeks.value} 周 ${props.lastDate}` : `第 ${totalWeeks.value} 周`
);

/** 至少占两周才写名字：一周以内的阶段放不下中文，只留色块，明细在下面列表里 */
const labelSegments = computed(() =>
  props.segments.filter(segment => segment.endValue - segment.startValue >= 2)
);

const labelGridStyle = computed(() => ({
  gridTemplateColumns: `repeat(${totalWeeks.value * 2}, minmax(0, 1fr))`
}));

const weekGridStyle = computed(() => {
  const step = 100 / totalWeeks.value;
  return {
    backgroundImage: `repeating-linear-gradient(to right, transparent 0 ${step}%, var(--border-hairline) ${step}% ${step + 0.35}%)`
  };
});

const currentColumnStyle = computed(() => ({
  left: `${((props.currentWeek - 1) / totalWeeks.value) * 100}%`,
  width: `${100 / totalWeeks.value}%`
}));

function labelColumnStyle(segment) {
  return {
    gridColumn: `${Math.round(segment.startValue * 2) + 1} / ${Math.round(segment.endValue * 2) + 1}`
  };
}

function blockClass(segment) {
  return stageClass(segment.group);
}

function titleOf(segment) {
  const base = `${segment.label} · ${formatWeekRange(segment)}`;
  return segment.note ? `${base} · ${segment.note}` : base;
}

function blockStyle(segment) {
  const start = (segment.startValue / totalWeeks.value) * 100;
  const width = ((segment.endValue - segment.startValue) / totalWeeks.value) * 100;
  return {
    left: `${start}%`,
    width: `${Math.max(width, 0.6)}%`,
    ...stageStyle(segment.group, segment.key)
  };
}
</script>
