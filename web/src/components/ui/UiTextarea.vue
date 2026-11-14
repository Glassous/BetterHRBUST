<template>
  <div class="relative w-full">
    <textarea
      v-bind="$attrs"
      :value="modelValue"
      :placeholder="placeholder"
      :disabled="disabled"
      :readonly="readonly"
      :rows="rows"
      :maxlength="maxlength > 0 ? maxlength : undefined"
      :class="[
        'w-full rounded-[var(--radius-control)] transition-all duration-150 outline-none resize-y leading-relaxed',
        'bg-[var(--bg-subtle)] hover:border-[var(--border-strong)] border border-[var(--border-hairline)]',
        'text-[var(--ink-primary)] placeholder:text-[var(--ink-muted)]',
        'focus:bg-[var(--bg-surface)] focus:border-[var(--accent)] focus:ring-2 focus:ring-[var(--accent-soft)]',
        'disabled:opacity-50 disabled:cursor-not-allowed',
        size === 'sm' ? 'px-3.5 py-2 text-[13px]' : 'px-4 py-3 text-sm',
        customClass
      ]"
      @blur="$emit('blur', $event)"
      @input="$emit('update:modelValue', $event.target.value)"
    />
    <div
      v-if="$slots.hint"
      class="mt-2 flex items-center justify-between gap-3 text-[13px] text-[var(--ink-muted)]"
    >
      <slot name="hint" />
    </div>
  </div>
</template>

<script setup>
defineOptions({ inheritAttrs: false });

defineProps({
  modelValue: {
    type: String,
    default: ''
  },
  placeholder: {
    type: String,
    default: ''
  },
  rows: {
    type: Number,
    default: 6
  },
  // 为 0 表示不限制输入长度（长度仍由页面自行校验）
  maxlength: {
    type: Number,
    default: 0
  },
  size: {
    type: String,
    default: 'md'
  },
  disabled: {
    type: Boolean,
    default: false
  },
  readonly: {
    type: Boolean,
    default: false
  },
  customClass: {
    type: String,
    default: ''
  }
});

defineEmits(['update:modelValue', 'blur']);
</script>
