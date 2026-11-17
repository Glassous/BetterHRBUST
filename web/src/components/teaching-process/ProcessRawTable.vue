<template>
  <div>
    <!-- 桌面：与教务处原表逐格对应的周网格 -->
    <UiScrollArea axis="both" region-label="本科教学进程周网格表" class="hidden rounded-[var(--radius-panel)] border border-[var(--border-hairline)] md:block" viewport-class="max-h-[70vh]">
      <table class="w-full min-w-[1080px] border-collapse text-[11px] leading-none">
        <caption class="sr-only">本科教学进程周网格表</caption>
        <thead class="sticky top-0 z-20 bg-[var(--bg-surface)]">
          <tr class="border-b border-[var(--border-hairline)]">
            <th scope="col" rowspan="3" class="sticky left-0 z-30 w-32 bg-[var(--bg-surface)] px-3 py-2 text-left text-[12px] font-semibold text-[var(--ink-secondary)]">
              班级 / 周次
            </th>
            <th
              v-for="week in weeks"
              :key="`w-${week.w}`"
              scope="col"
              :class="[
                'px-1 py-1.5 text-center font-medium num-tabular',
                week.w === currentWeek
                  ? 'bg-[var(--accent)] text-[var(--accent-ink)]'
                  : 'text-[var(--ink-secondary)]'
              ]"
            >
              {{ week.w }}
            </th>
          </tr>
          <tr class="border-b border-[var(--border-hairline)] text-[10px] text-[var(--ink-muted)]">
            <th
              v-for="week in weeks"
              :key="`mon-${week.w}`"
              :class="['px-1 py-1 text-center font-normal num-tabular', week.w === currentWeek ? 'bg-[var(--accent-soft)]' : '']"
            >
              {{ dayOf(week.mon) }}
            </th>
          </tr>
          <tr class="border-b border-[var(--border-strong)] text-[10px] text-[var(--ink-muted)]">
            <th
              v-for="week in weeks"
              :key="`sun-${week.w}`"
              :class="['px-1 py-1 text-center font-normal num-tabular', week.w === currentWeek ? 'bg-[var(--accent-soft)]' : '']"
            >
              {{ dayOf(week.sun) }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.name" class="border-b border-[var(--border-hairline)] last:border-0">
            <th
              scope="row"
              :class="[
                'sticky left-0 z-10 max-w-[11rem] truncate bg-[var(--bg-surface)] px-3 py-1.5 text-left text-[12px] font-medium',
                row.name === mine ? 'text-[var(--accent)]' : 'text-[var(--ink-primary)]'
              ]"
              :title="row.name"
            >
              {{ row.name }}
            </th>
            <td
              v-for="(cell, index) in row.cells"
              :key="index"
              :class="['relative h-8 min-w-[34px] border-l border-[var(--border-hairline)] px-0.5 text-center text-[11px] text-[var(--ink-primary)]', index + 1 === currentWeek ? 'bg-[var(--accent-soft)]/40 ring-1 ring-inset ring-[var(--accent-border)]' : '']"
            >
              <span v-if="cell.stage.length" class="absolute inset-0 flex" aria-hidden="true">
                <span
                  v-for="(part, partIndex) in cell.stage"
                  :key="partIndex"
                  :class="['flex-1', stageClass(part.group)]"
                  :style="stageStyle(part.group, part.key)"
                ></span>
              </span>
              <span class="relative z-10">{{ cell.raw }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </UiScrollArea>

    <!-- 移动端：每班一行色块，避免 26 列横向拖拽 -->
    <div class="divide-y divide-[var(--border-hairline)] rounded-[var(--radius-panel)] border border-[var(--border-hairline)] md:hidden">
      <div v-for="row in rows" :key="`m-${row.name}`" class="flex items-center gap-3 px-3 py-2">
        <span :class="['w-24 shrink-0 truncate text-[12px]', row.name === mine ? 'font-semibold text-[var(--accent)]' : 'text-[var(--ink-primary)]']">
          {{ row.name }}
        </span>
        <span class="flex flex-1 gap-[2px]">
          <span
            v-for="(cell, index) in row.cells"
            :key="index"
            :class="['h-4 flex-1 rounded-[2px]', stageClass(primary(cell).group), index + 1 === currentWeek ? 'ring-1 ring-[var(--accent)]' : '']"
            :style="stageStyle(primary(cell).group, primary(cell).key)"
            :title="`第 ${index + 1} 周：${cell.raw || '常规教学'}`"
          ></span>
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import UiScrollArea from '@/components/ui/UiScrollArea.vue';
import { decodePattern } from '@/utils/teachingProcess.js';
import { cellParts, stageClass, stageStyle } from '@/utils/teachingProcessSymbols.js';

const props = defineProps({
  classes: { type: Array, default: () => [] },
  patterns: { type: Array, default: () => [] },
  weeks: { type: Array, default: () => [] },
  currentWeek: { type: Number, default: 0 },
  mine: { type: String, default: '' }
});

// 原表只有几十种格内容，缓存解析结果避免逐格重复计算
const partsCache = new Map();
function partsOf(raw) {
  const key = raw || '';
  if (!partsCache.has(key)) partsCache.set(key, cellParts(key));
  return partsCache.get(key);
}

const rows = computed(() =>
  props.classes.map(item => ({
    name: item.name,
    cells: decodePattern(props.patterns[item.patternIndex]).map(raw => {
      const parts = partsOf(raw);
      // 常规教学周不画色块，减少近四成的空节点
      return { raw, parts, stage: parts.filter(part => part.group !== 'idle') };
    })
  }))
);

function dayOf(iso) {
  const [, month, day] = iso.split('-');
  return `${Number(month)}/${day}`;
}

/** 移动端色块只画一层：优先取该周第一个非「常规教学」的阶段 */
function primary(cell) {
  return cell.stage[0] || cell.parts[0];
}
</script>
