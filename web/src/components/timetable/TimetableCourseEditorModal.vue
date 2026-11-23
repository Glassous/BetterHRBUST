<template>
  <UiModal
    :model-value="modelValue"
    :title="isEditing ? '编辑实验 / 自定义课程' : '添加实验 / 自定义课程'"
    max-width="md"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <form :id="formId" class="space-y-4" novalidate @submit.prevent="onSubmit">
      <!-- 课程类型 -->
      <fieldset class="space-y-1.5">
        <legend class="text-xs font-semibold text-[var(--ink-secondary)]">课程类型</legend>
        <div class="grid grid-cols-2 gap-2">
          <label
            v-for="item in CUSTOM_COURSE_CATEGORIES"
            :key="item.value"
            :class="[
              'flex items-center justify-center gap-2 rounded-[var(--shape-md)] border px-3 py-2.5 text-[13px] font-medium cursor-pointer transition-colors min-h-[42px]',
              form.category === item.value
                ? 'bg-[var(--md-secondary-container)] text-[var(--md-on-secondary-container)] border-transparent font-semibold'
                : 'bg-[var(--md-surface-container)] text-[var(--ink-secondary)] border-[var(--border-hairline)] hover:bg-[var(--md-surface-container-high)]'
            ]"
          >
            <input
              v-model="form.category"
              type="radio"
              name="course-category"
              :value="item.value"
              class="sr-only"
            />
            <Icon :name="item.value === 'lab' ? 'flask' : 'timetable'" customClass="w-4 h-4" />
            <span>{{ item.label }}</span>
          </label>
        </div>
      </fieldset>

      <!-- 快捷填充本学期课程 / 未排课实验 -->
      <div v-if="!isEditing && quickCourseSuggestions.length > 0" class="space-y-1.5">
        <div class="text-xs font-medium text-[var(--ink-muted)]">快捷填入本学期课程（可选）</div>
        <div class="flex flex-wrap gap-1.5 max-h-24 overflow-y-auto pr-1">
          <button
            v-for="item in quickCourseSuggestions"
            :key="item.key"
            type="button"
            class="px-2.5 py-1 rounded-full text-xs bg-[var(--md-surface-container)] hover:bg-[var(--md-primary-container)] text-[var(--ink-secondary)] hover:text-[var(--md-on-primary-container)] border border-[var(--border-hairline)] cursor-pointer transition-colors truncate max-w-[200px]"
            :title="`${item.courseName}${item.teacher ? ' · ' + item.teacher : ''}`"
            @click="applyQuickSuggestion(item)"
          >
            {{ item.courseName }}
          </button>
        </div>
      </div>

      <!-- 课程名称 -->
      <div class="space-y-1">
        <label :for="`${uid}-name`" class="block text-xs font-semibold text-[var(--ink-secondary)]">
          {{ form.category === 'lab' ? '实验名称' : '课程名称' }}
          <span class="text-[var(--sem-danger)]" aria-hidden="true">*</span>
        </label>
        <input
          :id="`${uid}-name`"
          ref="nameInputRef"
          v-model="form.courseName"
          type="text"
          name="courseName"
          required
          maxlength="60"
          :placeholder="form.category === 'lab' ? '例如：大学物理实验（一）' : '例如：学科竞赛培训'"
          :aria-invalid="errors.courseName ? 'true' : 'false'"
          :aria-describedby="errors.courseName ? `${uid}-name-err` : undefined"
          class="tt-form-input"
          @blur="touchField('courseName')"
          @input="clearError('courseName')"
        />
        <p v-if="errors.courseName" :id="`${uid}-name-err`" class="text-xs text-[var(--sem-danger)]">
          {{ errors.courseName }}
        </p>
      </div>

      <!-- 周次与星期 -->
      <div class="grid grid-cols-2 gap-3">
        <div class="space-y-1">
          <label :for="`${uid}-week`" class="block text-xs font-semibold text-[var(--ink-secondary)]">所在教学周</label>
          <select :id="`${uid}-week`" v-model.number="form.week" name="week" class="tt-form-input">
            <option v-for="w in weekOptions" :key="w" :value="w">第 {{ w }} 周</option>
          </select>
        </div>
        <div class="space-y-1">
          <label :for="`${uid}-day`" class="block text-xs font-semibold text-[var(--ink-secondary)]">星期</label>
          <select :id="`${uid}-day`" v-model.number="form.day" name="day" class="tt-form-input">
            <option v-for="(label, idx) in TIMETABLE_DAY_NAMES" :key="label" :value="idx + 1">
              {{ label }}
            </option>
          </select>
        </div>
      </div>

      <!-- 上课时间方式：按节次 或 自定义几点到几点 -->
      <fieldset class="space-y-2.5 rounded-[var(--shape-lg)] bg-[var(--md-surface-container)]/65 p-3.5 border border-[var(--border-hairline)]">
        <legend class="px-1 text-xs font-semibold text-[var(--ink-secondary)]">时间安排</legend>
        <div class="grid grid-cols-2 gap-2">
          <label
            :class="[
              'flex items-center justify-center gap-1.5 rounded-[var(--shape-md)] border px-3 py-2 text-xs font-medium cursor-pointer transition-colors min-h-[38px]',
              form.timeMode === 'slot'
                ? 'bg-[var(--md-primary-container)] text-[var(--md-on-primary-container)] border-transparent font-semibold'
                : 'bg-[var(--bg-surface)] text-[var(--ink-secondary)] border-[var(--border-hairline)]'
            ]"
          >
            <input v-model="form.timeMode" type="radio" name="time-mode" value="slot" class="sr-only" />
            <span>选择第几节</span>
          </label>
          <label
            :class="[
              'flex items-center justify-center gap-1.5 rounded-[var(--shape-md)] border px-3 py-2 text-xs font-medium cursor-pointer transition-colors min-h-[38px]',
              form.timeMode === 'custom'
                ? 'bg-[var(--md-primary-container)] text-[var(--md-on-primary-container)] border-transparent font-semibold'
                : 'bg-[var(--bg-surface)] text-[var(--ink-secondary)] border-[var(--border-hairline)]'
            ]"
          >
            <input v-model="form.timeMode" type="radio" name="time-mode" value="custom" class="sr-only" />
            <span>自定义几点到几点</span>
          </label>
        </div>

        <!-- 按节次 -->
        <div v-if="form.timeMode === 'slot'" class="space-y-2.5">
          <div class="grid grid-cols-2 gap-2.5">
            <div class="space-y-1">
              <label :for="`${uid}-slot-type`" class="block text-xs text-[var(--ink-muted)]">节次粒度</label>
              <select
                :id="`${uid}-slot-type`"
                v-model="form.slotType"
                name="slotType"
                class="tt-form-input"
                @change="onSlotTypeChange"
              >
                <option value="combine">按大节（1–6 大节）</option>
                <option value="base">按小节（1–12 小节）</option>
              </select>
            </div>
            <div class="space-y-1">
              <label :for="`${uid}-slot-period`" class="block text-xs text-[var(--ink-muted)]">
                {{ form.slotType === 'combine' ? '第几大节' : '起始小节' }}
              </label>
              <select
                :id="`${uid}-slot-period`"
                v-model.number="form.slotPeriod"
                name="slotPeriod"
                class="tt-form-input"
              >
                <option v-for="slot in slotPeriodOptions" :key="slot.period" :value="slot.period">
                  {{ form.slotType === 'combine' ? `第 ${slot.period} 大节` : `第 ${slot.period} 节` }} ({{ slot.time }})
                </option>
              </select>
            </div>
          </div>

          <div v-if="form.slotType === 'base'" class="space-y-1">
            <label :for="`${uid}-slot-span`" class="block text-xs text-[var(--ink-muted)]">连续小节数</label>
            <select :id="`${uid}-slot-span`" v-model.number="form.slotSpan" name="slotSpan" class="tt-form-input">
              <option :value="1">1 小节（单节）</option>
              <option :value="2">2 小节（连堂）</option>
              <option :value="3">3 小节</option>
              <option :value="4">4 小节</option>
            </select>
          </div>
        </div>

        <!-- 自定义时间 -->
        <div v-else class="space-y-2">
          <div class="grid grid-cols-2 gap-3">
            <div class="space-y-1">
              <label :for="`${uid}-start-time`" class="block text-xs text-[var(--ink-muted)]">
                开始时间 <span class="text-[var(--sem-danger)]" aria-hidden="true">*</span>
              </label>
              <input
                :id="`${uid}-start-time`"
                ref="startTimeInputRef"
                v-model="form.startTime"
                type="time"
                name="startTime"
                required
                :aria-invalid="errors.customTime ? 'true' : 'false'"
                :aria-describedby="errors.customTime ? `${uid}-time-err` : `${uid}-time-anchor`"
                class="tt-form-input num-tabular"
                @blur="touchField('customTime')"
                @input="clearError('customTime')"
              />
            </div>
            <div class="space-y-1">
              <label :for="`${uid}-end-time`" class="block text-xs text-[var(--ink-muted)]">
                结束时间 <span class="text-[var(--sem-danger)]" aria-hidden="true">*</span>
              </label>
              <input
                :id="`${uid}-end-time`"
                ref="endTimeInputRef"
                v-model="form.endTime"
                type="time"
                name="endTime"
                required
                :aria-invalid="errors.customTime ? 'true' : 'false'"
                :aria-describedby="errors.customTime ? `${uid}-time-err` : `${uid}-time-anchor`"
                class="tt-form-input num-tabular"
                @blur="touchField('customTime')"
                @input="clearError('customTime')"
              />
            </div>
          </div>
          <p v-if="errors.customTime" :id="`${uid}-time-err`" class="text-xs text-[var(--sem-danger)]">
            {{ errors.customTime }}
          </p>
          <p v-else :id="`${uid}-time-anchor`" class="text-xs text-[var(--ink-muted)]">
            {{ anchorPreviewText }}
          </p>
        </div>
      </fieldset>

      <!-- 上课地点与指导教师 -->
      <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <div class="space-y-1">
          <label :for="`${uid}-location`" class="block text-xs font-semibold text-[var(--ink-secondary)]">
            {{ form.category === 'lab' ? '实验室 / 教室' : '上课地点' }}
          </label>
          <input
            :id="`${uid}-location`"
            v-model="form.location"
            type="text"
            name="location"
            maxlength="60"
            placeholder="例如：西区实验楼 B304"
            class="tt-form-input"
          />
        </div>
        <div class="space-y-1">
          <label :for="`${uid}-teacher`" class="block text-xs font-semibold text-[var(--ink-secondary)]">指导教师</label>
          <input
            :id="`${uid}-teacher`"
            v-model="form.teacher"
            type="text"
            name="teacher"
            maxlength="40"
            placeholder="选填"
            class="tt-form-input"
          />
        </div>
      </div>

      <!-- 备注 -->
      <div class="space-y-1">
        <label :for="`${uid}-note`" class="block text-xs font-semibold text-[var(--ink-secondary)]">备注 / 分组批号</label>
        <input
          :id="`${uid}-note`"
          v-model="form.note"
          type="text"
          name="note"
          maxlength="80"
          placeholder="选填，如：第一组、需带实验报告"
          class="tt-form-input"
        />
      </div>
    </form>

    <template #footer>
      <div class="flex items-center justify-between gap-2 w-full">
        <UiButton
          v-if="isEditing"
          type="button"
          variant="danger"
          size="sm"
          @click="$emit('delete', form.id)"
        >
          <template #prefix>
            <Icon name="trash" customClass="w-3.5 h-3.5" />
          </template>
          删除
        </UiButton>
        <span v-else />

        <div class="flex items-center gap-2">
          <UiButton type="button" variant="outline" size="sm" @click="$emit('update:modelValue', false)">
            取消
          </UiButton>
          <UiButton type="submit" :form="formId" variant="primary" size="sm">
            {{ isEditing ? '保存修改' : '添加到课表' }}
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
import Icon from '@/components/icons/Icon.vue';
import { BASE_SLOT_TIMES, COMBINE_SLOT_TIMES, parseSlotRangeText, parseTimeToMinutes } from '@/utils/periodTimes.js';
import {
  CUSTOM_COURSE_CATEGORIES,
  TIMETABLE_DAY_NAMES,
  normalizeCustomCourse,
  resolveCustomTimeAnchor
} from '@/utils/timetableOverrides.js';

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  initialData: { type: Object, default: null },
  maxWeeks: { type: Number, default: 20 },
  currentCourses: { type: Array, default: () => [] },
  unarrangedCourses: { type: Array, default: () => [] }
});

const emit = defineEmits(['update:modelValue', 'save', 'delete']);

const uid = useId().replace(/[^a-zA-Z0-9_-]/g, '');
const formId = `tt-course-editor-${uid}`;

const nameInputRef = ref(null);
const startTimeInputRef = ref(null);
const endTimeInputRef = ref(null);

const form = reactive({
  id: '',
  category: 'lab',
  courseName: '',
  location: '',
  teacher: '',
  week: 1,
  day: 1,
  timeMode: 'slot',
  slotType: 'combine',
  slotPeriod: 1,
  slotSpan: 1,
  startTime: '14:00',
  endTime: '16:00',
  note: ''
});

const errors = reactive({
  courseName: '',
  customTime: ''
});

const isEditing = computed(() => Boolean(form.id));

const weekOptions = computed(() => {
  const count = Math.max(1, Math.min(26, Number(props.maxWeeks) || 20));
  return Array.from({ length: count }, (_, i) => i + 1);
});

const slotPeriodOptions = computed(() =>
  form.slotType === 'base' ? BASE_SLOT_TIMES : COMBINE_SLOT_TIMES
);

const quickCourseSuggestions = computed(() => {
  const seen = new Set();
  const list = [];
  const pushItem = (courseName, teacher = '') => {
    const name = String(courseName || '').trim();
    if (!name || seen.has(name)) return;
    seen.add(name);
    list.push({ key: name, courseName: name, teacher: String(teacher || '').trim() });
  };
  for (const item of props.unarrangedCourses || []) pushItem(item.courseName, item.teacher);
  for (const item of props.currentCourses || []) pushItem(item.courseName, item.teacher);
  return list;
});

const anchorPreviewText = computed(() => {
  const startMin = parseTimeToMinutes(form.startTime);
  const endMin = parseTimeToMinutes(form.endTime);
  if (startMin === null || endMin === null || endMin <= startMin) {
    return '填写开始与结束时间后，将自动对齐到最相近的课表时段格';
  }
  const anchor = resolveCustomTimeAnchor(form.startTime, form.endTime);
  return `将停靠在课表第 ${anchor.combineSection} 大节（第 ${anchor.baseSection} 小节）网格位，卡片展示精确时间 ${form.startTime} - ${form.endTime}`;
});

function defaultTimesForSlot(slotType, period) {
  const slots = slotType === 'base' ? BASE_SLOT_TIMES : COMBINE_SLOT_TIMES;
  const target = slots.find(s => s.period === Number(period)) || slots[0];
  const parsed = parseSlotRangeText(target?.time);
  return {
    startTime: parsed?.start || '14:00',
    endTime: parsed?.end || '15:40'
  };
}

function hydrateForm(data) {
  const source = data || {};
  const slotType = source.slotType === 'base' ? 'base' : 'combine';
  const maxPeriod = slotType === 'base' ? BASE_SLOT_TIMES.length : COMBINE_SLOT_TIMES.length;
  const slotPeriod = Math.min(maxPeriod, Math.max(1, Number(source.slotPeriod) || 1));
  const defaults = defaultTimesForSlot(slotType, slotPeriod);

  form.id = String(source.id || '');
  form.category = source.category === 'custom' ? 'custom' : 'lab';
  form.courseName = String(source.courseName || '');
  form.location = String(source.location || '');
  form.teacher = String(source.teacher || '');
  form.week = Math.max(1, Math.min(26, Number(source.week) || 1));
  form.day = Math.max(1, Math.min(7, Number(source.day) || 1));
  form.timeMode = source.timeMode === 'custom' ? 'custom' : 'slot';
  form.slotType = slotType;
  form.slotPeriod = slotPeriod;
  form.slotSpan = slotType === 'base' ? Math.min(4, Math.max(1, Number(source.slotSpan) || 1)) : 1;
  form.startTime = String(source.startTime || defaults.startTime);
  form.endTime = String(source.endTime || defaults.endTime);
  form.note = String(source.note || '');
  errors.courseName = '';
  errors.customTime = '';
}

watch(
  () => [props.modelValue, props.initialData],
  ([open]) => {
    if (open) hydrateForm(props.initialData);
  },
  { immediate: true }
);

function onSlotTypeChange() {
  if (form.slotType === 'combine' && form.slotPeriod > COMBINE_SLOT_TIMES.length) {
    form.slotPeriod = Math.ceil(form.slotPeriod / 2);
  }
}

function applyQuickSuggestion(item) {
  form.courseName = item.courseName;
  if (item.teacher && !form.teacher) form.teacher = item.teacher;
  clearError('courseName');
}

function validateCourseName() {
  if (!form.courseName.trim()) {
    errors.courseName = '请填写实验或课程名称';
    return false;
  }
  errors.courseName = '';
  return true;
}

function validateCustomTime() {
  if (form.timeMode !== 'custom') {
    errors.customTime = '';
    return true;
  }
  const startMin = parseTimeToMinutes(form.startTime);
  const endMin = parseTimeToMinutes(form.endTime);
  if (startMin === null || endMin === null) {
    errors.customTime = '请完整填写开始和结束时间';
    return false;
  }
  if (endMin <= startMin) {
    errors.customTime = '结束时间必须晚于开始时间';
    return false;
  }
  errors.customTime = '';
  return true;
}

function touchField(field) {
  if (field === 'courseName') validateCourseName();
  if (field === 'customTime') validateCustomTime();
}

function clearError(field) {
  errors[field] = '';
}

function onSubmit() {
  const okName = validateCourseName();
  const okTime = validateCustomTime();
  if (!okName) {
    nameInputRef.value?.focus();
    return;
  }
  if (!okTime) {
    if (parseTimeToMinutes(form.startTime) === null) startTimeInputRef.value?.focus();
    else endTimeInputRef.value?.focus();
    return;
  }

  emit('save', normalizeCustomCourse({ ...form }));
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
