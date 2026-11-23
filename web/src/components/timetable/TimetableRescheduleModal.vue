<template>
  <UiModal
    :model-value="modelValue"
    :title="existingId ? '编辑单周临时调课' : '确认单周临时调课'"
    max-width="md"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <form v-if="sourceMatch" :id="formId" class="space-y-4" novalidate @submit.prevent="onSubmit">
      <!-- 原安排对照卡片 -->
      <div class="rounded-[var(--shape-lg)] bg-[var(--md-surface-container)] p-3.5 border border-[var(--border-hairline)] space-y-1.5">
        <div class="flex items-center justify-between gap-2">
          <span class="text-sm font-semibold text-[var(--ink-primary)] truncate">{{ sourceMatch.courseName }}</span>
          <UiBadge size="sm" variant="accent">第 {{ week }} 周临时调课</UiBadge>
        </div>
        <div class="text-xs text-[var(--ink-secondary)] flex flex-wrap gap-x-3 gap-y-1">
          <span>原位置：{{ originPositionLabel }}</span>
          <span>原教室：{{ sourceMatch.originLocation || '待定' }}</span>
        </div>
      </div>

      <!-- 调整后位置 -->
      <div class="grid grid-cols-2 gap-3">
        <div class="space-y-1">
          <label :for="`${uid}-day`" class="block text-xs font-semibold text-[var(--ink-secondary)]">调整到星期</label>
          <select
            :id="`${uid}-day`"
            ref="daySelectRef"
            v-model.number="form.targetDay"
            name="targetDay"
            :aria-invalid="positionError ? 'true' : 'false'"
            :aria-describedby="positionError ? `${uid}-pos-err` : undefined"
            class="tt-form-input"
            @change="validatePosition"
          >
            <option v-for="(label, idx) in TIMETABLE_DAY_NAMES" :key="label" :value="idx + 1">
              {{ label }}
            </option>
          </select>
        </div>

        <div class="space-y-1">
          <label :for="`${uid}-period`" class="block text-xs font-semibold text-[var(--ink-secondary)]">
            {{ viewMode === 'base' ? '调整到第几小节' : '调整到第几大节' }}
          </label>
          <select
            :id="`${uid}-period`"
            v-model.number="form.targetPeriod"
            name="targetPeriod"
            :aria-invalid="positionError ? 'true' : 'false'"
            :aria-describedby="positionError ? `${uid}-pos-err` : undefined"
            class="tt-form-input"
            @change="validatePosition"
          >
            <option v-for="slot in activeSlots" :key="slot.period" :value="slot.period">
              {{ viewMode === 'base' ? `第 ${slot.period} 节` : `第 ${slot.period} 大节` }} ({{ slot.time }})
            </option>
          </select>
        </div>
      </div>

      <p
        v-if="positionError"
        :id="`${uid}-pos-err`"
        role="alert"
        class="text-xs text-[var(--sem-danger)] bg-[var(--sem-danger-soft)] px-3 py-2 rounded-[var(--shape-md)]"
      >
        {{ positionError }}
      </p>

      <!-- 自定义教室与教师 -->
      <div class="space-y-1">
        <label :for="`${uid}-location`" class="block text-xs font-semibold text-[var(--ink-secondary)]">
          调整后教室（可单独自定义）
        </label>
        <input
          :id="`${uid}-location`"
          ref="locationInputRef"
          v-model="form.location"
          type="text"
          name="location"
          maxlength="60"
          placeholder="输入调整后的上课教室，留空显示“待定”"
          class="tt-form-input"
        />
      </div>

      <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <div class="space-y-1">
          <label :for="`${uid}-teacher`" class="block text-xs font-semibold text-[var(--ink-secondary)]">任课教师</label>
          <input
            :id="`${uid}-teacher`"
            v-model="form.teacher"
            type="text"
            name="teacher"
            maxlength="40"
            placeholder="默认沿用原教师"
            class="tt-form-input"
          />
        </div>
        <div class="space-y-1">
          <label :for="`${uid}-note`" class="block text-xs font-semibold text-[var(--ink-secondary)]">调课备注</label>
          <input
            :id="`${uid}-note`"
            v-model="form.note"
            type="text"
            name="note"
            maxlength="80"
            placeholder="选填，如：教师临时调课"
            class="tt-form-input"
          />
        </div>
      </div>
    </form>

    <template #footer>
      <div class="flex items-center justify-between gap-2 w-full">
        <UiButton
          v-if="existingId"
          type="button"
          variant="outline"
          size="sm"
          @click="$emit('restore', existingId)"
        >
          <template #prefix>
            <Icon name="refresh" customClass="w-3.5 h-3.5" />
          </template>
          还原原排课
        </UiButton>
        <span v-else />

        <div class="flex items-center gap-2">
          <UiButton type="button" variant="outline" size="sm" @click="$emit('update:modelValue', false)">
            取消
          </UiButton>
          <UiButton type="submit" :form="formId" variant="primary" size="sm">
            确认调课
          </UiButton>
        </div>
      </div>
    </template>
  </UiModal>
</template>

<script setup>
import { computed, reactive, ref, useId, watch } from 'vue';
import UiModal from '@/components/ui/UiModal.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiBadge from '@/components/ui/UiBadge.vue';
import Icon from '@/components/icons/Icon.vue';
import { BASE_SLOT_TIMES, COMBINE_SLOT_TIMES } from '@/utils/periodTimes.js';
import {
  TIMETABLE_DAY_NAMES,
  formatDaySectionLabel,
  isDifferentRescheduleTarget,
  normalizeTimetableReschedule
} from '@/utils/timetableOverrides.js';

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  week: { type: Number, default: 1 },
  viewMode: { type: String, default: 'combine' },
  sourceMatch: { type: Object, default: null },
  existingId: { type: String, default: '' },
  initialTargetDay: { type: Number, default: 1 },
  initialTargetPeriod: { type: Number, default: 1 },
  initialLocation: { type: String, default: '' },
  initialTeacher: { type: String, default: '' },
  initialNote: { type: String, default: '' }
});

const emit = defineEmits(['update:modelValue', 'save', 'restore']);

const uid = useId().replace(/[^a-zA-Z0-9_-]/g, '');
const formId = `tt-reschedule-${uid}`;
const daySelectRef = ref(null);
const locationInputRef = ref(null);

const form = reactive({
  targetDay: 1,
  targetPeriod: 1,
  location: '',
  teacher: '',
  note: ''
});

const positionError = ref('');

const activeSlots = computed(() =>
  props.viewMode === 'base' ? BASE_SLOT_TIMES : COMBINE_SLOT_TIMES
);

const originPositionLabel = computed(() => {
  if (!props.sourceMatch) return '';
  const period = props.viewMode === 'base'
    ? (props.sourceMatch.originBaseSections?.[0] || (props.sourceMatch.originCombineSection - 1) * 2 + 1)
    : props.sourceMatch.originCombineSection;
  return formatDaySectionLabel(props.sourceMatch.originDay, period, props.viewMode);
});

watch(
  () => [props.modelValue, props.sourceMatch, props.initialTargetDay, props.initialTargetPeriod],
  ([open]) => {
    if (!open || !props.sourceMatch) return;
    form.targetDay = Number(props.initialTargetDay) || props.sourceMatch.originDay || 1;
    form.targetPeriod = Number(props.initialTargetPeriod) || 1;
    form.location = String(props.initialLocation ?? props.sourceMatch.originLocation ?? '');
    form.teacher = String(props.initialTeacher ?? props.sourceMatch.originTeacher ?? '');
    form.note = String(props.initialNote || '');
    positionError.value = '';
  },
  { immediate: true }
);

function validatePosition() {
  if (!props.sourceMatch) return false;
  if (!isDifferentRescheduleTarget(props.sourceMatch, form.targetDay, form.targetPeriod, props.viewMode)) {
    positionError.value = '调整后的课所在位置必须与原有位置不同，请选择其它星期或节次';
    return false;
  }
  positionError.value = '';
  return true;
}

function onSubmit() {
  if (!validatePosition()) {
    daySelectRef.value?.focus();
    return;
  }
  const payload = normalizeTimetableReschedule({
    id: props.existingId || undefined,
    week: props.week,
    sourceMatch: props.sourceMatch,
    targetDay: form.targetDay,
    targetSectionMode: props.viewMode === 'base' ? 'base' : 'combine',
    targetCombineSection: props.viewMode === 'base' ? Math.ceil(form.targetPeriod / 2) : form.targetPeriod,
    targetBaseSection: props.viewMode === 'base' ? form.targetPeriod : (form.targetPeriod - 1) * 2 + 1,
    location: form.location,
    teacher: form.teacher,
    note: form.note
  });
  emit('save', payload);
  emit('update:modelValue', false);
}
</script>

<style scoped>
.tt-form-input {
  width: 100%;
  min-height: 40px;
  padding: 8px 12px;
  font-size: 13px;
  border-radius: var(--shape-md);
  background-color: var(--md-surface-container-highest);
  border: 1px solid transparent;
  color: var(--ink-primary);
  outline: none;
  transition: border-color 150ms ease, background-color 150ms ease;
}

.tt-form-input:hover {
  background-color: var(--md-surface-container-high);
}

.tt-form-input:focus {
  background-color: var(--bg-surface);
  border-color: var(--md-primary);
}

.tt-form-input[aria-invalid='true'] {
  border-color: var(--sem-danger);
  background-color: var(--sem-danger-soft);
}
</style>
