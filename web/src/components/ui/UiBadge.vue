<template>
  <span
    :class="[
      'inline-flex items-center whitespace-nowrap shrink-0 font-medium rounded-full select-none leading-none transition-colors duration-150',
      sizeClasses,
      variantClasses,
      customClass
    ]"
  >
    <span
      v-if="dot"
      :class="['w-1.5 h-1.5 rounded-full mr-1.5 shrink-0', dotClasses]"
    />
    <slot />
  </span>
</template>

<script setup>
import { computed } from 'vue';

const props = defineProps({
  variant: {
    type: String,
    default: 'default' // 'default' | 'accent' | 'success' | 'warning' | 'danger' | 'info' | 'outline'
  },
  size: {
    type: String,
    default: 'md' // 'sm' | 'md'
  },
  dot: {
    type: Boolean,
    default: false
  },
  customClass: {
    type: String,
    default: ''
  }
});

const sizeClasses = computed(() => {
  return props.size === 'sm' ? 'px-2.5 py-1 text-xs' : 'px-3 py-1.5 text-[13px]';
});

const variantClasses = computed(() => {
  switch (props.variant) {
    case 'accent':
    case 'info':
      return 'bg-[var(--accent-soft)] text-[var(--accent)] border border-[var(--accent-border)]';
    case 'success':
      return 'bg-[var(--sem-success-soft)] text-[var(--sem-success)] border border-[var(--sem-success-border)]';
    case 'warning':
      return 'bg-[var(--sem-warning-soft)] text-[var(--sem-warning)] border border-[var(--sem-warning-border)]';
    case 'danger':
      return 'bg-[var(--sem-danger-soft)] text-[var(--sem-danger)] border border-[var(--sem-danger-border)]';
    case 'outline':
      return 'bg-transparent text-[var(--ink-secondary)] border border-[var(--border-strong)]';
    default:
      return 'bg-[var(--bg-subtle)] text-[var(--ink-secondary)] border border-[var(--border-hairline)]';
  }
});

const dotClasses = computed(() => {
  switch (props.variant) {
    case 'accent':
    case 'info':
      return 'bg-[var(--accent)]';
    case 'success':
      return 'bg-[var(--sem-success)]';
    case 'warning':
      return 'bg-[var(--sem-warning)]';
    case 'danger':
      return 'bg-[var(--sem-danger)]';
    default:
      return 'bg-[var(--ink-muted)]';
  }
});
</script>