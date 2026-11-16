<template>
  <span
    :class="[
      'inline-flex items-center whitespace-nowrap shrink-0 font-medium rounded-[var(--shape-full)] select-none leading-none transition-colors duration-150',
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
  return props.size === 'sm' ? 'px-2.5 py-1 text-[11px] h-5' : 'px-3 py-1.5 text-[12px] h-6';
});

const variantClasses = computed(() => {
  switch (props.variant) {
    case 'accent':
    case 'info':
      return 'bg-[var(--md-primary-container)] text-[var(--md-on-primary-container)]';
    case 'success':
      return 'bg-[var(--sem-success-soft)] text-[var(--sem-success)]';
    case 'warning':
      return 'bg-[var(--sem-warning-soft)] text-[var(--sem-warning)]';
    case 'danger':
      return 'bg-[var(--sem-danger-soft)] text-[var(--sem-danger)]';
    case 'outline':
      return 'bg-transparent text-[var(--ink-secondary)] border border-[var(--md-outline-variant)]';
    default:
      return 'bg-[var(--md-surface-container-high)] text-[var(--ink-secondary)]';
  }
});

const dotClasses = computed(() => {
  switch (props.variant) {
    case 'accent':
    case 'info':
      return 'bg-[var(--md-primary)]';
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
