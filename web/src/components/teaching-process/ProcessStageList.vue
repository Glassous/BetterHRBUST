<template>
  <ul class="space-y-0.5">
    <li
      v-for="(segment, index) in segments"
      :key="`${segment.key}-${segment.startWeek}-${index}`"
      :class="[
        'flex items-stretch gap-3 rounded-[var(--radius-panel)] px-3 py-2 transition-colors',
        statusOf(segment) === 'current' ? 'bg-[var(--accent-soft)]/70 ring-1 ring-inset ring-[var(--accent-border)]' : '',
        statusOf(segment) === 'past' ? 'opacity-55' : ''
      ]"
    >
      <span class="w-1 shrink-0 rounded-full" :style="barStyle(segment)"></span>

      <div class="min-w-0 flex-1">
        <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
          <span
            :class="['text-[13px] text-[var(--ink-primary)]', statusOf(segment) === 'current' ? 'font-semibold' : 'font-medium']"
            :title="segment.label"
          >
            {{ segment.short }}
          </span>
          <span v-if="statusOf(segment) === 'current'" class="text-[11px] font-semibold text-[var(--accent)]">进行中</span>
          <span v-else-if="statusOf(segment) === 'past'" class="text-[11px] text-[var(--ink-muted)]">已结束</span>
          <span v-else-if="currentWeek" class="text-[11px] text-[var(--ink-muted)]">
            {{ segment.startWeek - currentWeek }} 周后开始
          </span>
        </div>

        <div class="mt-0.5 flex flex-wrap items-center gap-x-2.5 gap-y-0.5 text-[12px] text-[var(--ink-muted)] num-tabular">
          <span>{{ formatWeekRange(segment) }}</span>
          <span v-if="dateRange(segment)">{{ dateRange(segment) }}</span>
          <span>{{ weekText(segment) }}</span>
          <span v-if="segment.note">{{ segment.note }}</span>
        </div>
      </div>
    </li>
  </ul>
</template>

<script setup>
import { formatWeekRange, stageStatusOf } from '@/utils/teachingProcess.js';
import { stageDotStyle } from '@/utils/teachingProcessSymbols.js';

const props = defineProps({
  segments: { type: Array, default: () => [] },
  weeks: { type: Array, default: () => [] },
  currentWeek: { type: Number, default: 0 }
});

function statusOf(segment) {
  return stageStatusOf(segment, props.currentWeek);
}

function barStyle(segment) {
  const style = stageDotStyle(segment.group, segment.key);
  return style.backgroundColor ? { backgroundColor: style.backgroundColor } : { backgroundColor: 'var(--border-strong)' };
}

function weekText(segment) {
  const count = segment.weekCount;
  if (!count) return '';
  if (count < 1) return '半周';
  const whole = Math.floor(count);
  return Number.isInteger(count) ? `${whole} 周` : `${whole} 周半`;
}

function dateRange(segment) {
  const start = props.weeks[segment.startWeek - 1];
  const end = props.weeks[segment.endWeek - 1];
  if (!start || !end) return '';
  const format = iso => {
    const [, month, day] = iso.split('-');
    return `${Number(month)}/${day}`;
  };
  return `${format(start.mon)} – ${format(end.sun)}`;
}
</script>
