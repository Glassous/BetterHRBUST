<template>
  <div class="relative w-full">
    <div
      v-if="$slots.prefix"
      class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-[var(--ink-muted)]"
    >
      <slot name="prefix" />
    </div>

    <input
      v-bind="$attrs"
      :type="type"
      :value="modelValue"
      :placeholder="placeholder"
      :disabled="disabled"
      :readonly="readonly"
      :maxlength="maxlength > 0 ? maxlength : undefined"
      :autocomplete="autocomplete"
      :class="[
        'w-full rounded-[var(--radius-control)] transition-all duration-150 outline-none',
        'bg-[var(--bg-subtle)] hover:border-[var(--border-strong)] border border-[var(--border-hairline)]',
        'text-[var(--ink-primary)] placeholder:text-[var(--ink-muted)]',
        'focus:bg-[var(--bg-surface)] focus:border-[var(--accent)] focus:ring-2 focus:ring-[var(--accent-soft)]',
        'disabled:opacity-50 disabled:cursor-not-allowed',
        $slots.prefix ? 'pl-9.5' : 'pl-3.5',
        $slots.suffix || clearable ? 'pr-9.5' : 'pr-3.5',
        size === 'sm' ? 'py-1.5 text-[13px] min-h-[36px]' : 'py-2.5 text-sm min-h-[42px]',
        customClass
      ]"
      @blur="$emit('blur', $event)"
      @input="$emit('update:modelValue', $event.target.value)"
      @keydown.enter="$emit('enter', $event)"
    />

    <div
      v-if="$slots.suffix || (clearable && modelValue)"
      class="absolute inset-y-0 right-0 pr-2.5 flex items-center text-[var(--ink-muted)] hover:text-[var(--ink-primary)]"
    >
      <button
        v-if="clearable && modelValue"
        type="button"
        aria-label="清除输入"
        class="p-1 rounded-[var(--radius-badge)] cursor-pointer hover:bg-[var(--bg-hover)]"
        @click="$emit('update:modelValue', '')"
      >
        <Icon name="x" customClass="w-3.5 h-3.5" />
      </button>
      <slot name="suffix" />
    </div>
  </div>
</template>

<script setup>
import Icon from '@/components/icons/Icon.vue';

defineOptions({ inheritAttrs: false });

defineProps({
  modelValue: {
    type: [String, Number],
    default: ''
  },
  placeholder: {
    type: String,
    default: ''
  },
  type: {
    type: String,
    default: 'text'
  },
  size: {
    type: String,
    default: 'md'
  },
  clearable: {
    type: Boolean,
    default: false
  },
  disabled: {
    type: Boolean,
    default: false
  },
  readonly: {
    type: Boolean,
    default: false
  },
  // 为 0 表示不限制长度
  maxlength: {
    type: Number,
    default: 0
  },
  // 账号类输入框建议显式声明，便于浏览器与密码管理器正确识别
  autocomplete: {
    type: String,
    default: 'off'
  },
  customClass: {
    type: String,
    default: ''
  }
});

defineEmits(['update:modelValue', 'enter', 'blur']);
</script>