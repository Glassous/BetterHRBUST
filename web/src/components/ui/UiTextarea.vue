<template>
  <div class="relative w-full">
    <textarea
      ref="textareaRef"
      v-bind="$attrs"
      :id="id || undefined"
      :value="modelValue"
      :placeholder="placeholder"
      :disabled="disabled"
      :readonly="readonly"
      :rows="rows"
      :maxlength="maxlength > 0 ? maxlength : undefined"
      :class="[
        'w-full rounded-[var(--shape-md)] transition-all duration-150 outline-none resize-y leading-relaxed',
        'bg-[var(--md-surface-container-high)] hover:bg-[var(--md-surface-container-highest)]',
        'border border-transparent',
        'text-[var(--ink-primary)] placeholder:text-[var(--ink-muted)]',
        'focus:bg-[var(--bg-surface)] focus:border-[var(--md-primary)]',
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
import { ref } from 'vue';

defineOptions({ inheritAttrs: false });

const textareaRef = ref(null);

defineProps({
  id: {
    type: String,
    default: ''
  },
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

defineExpose({
  focus: () => textareaRef.value?.focus()
});
</script>
