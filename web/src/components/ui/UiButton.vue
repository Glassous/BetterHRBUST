<template>
  <button
    :type="type"
    :disabled="disabled || loading"
    :class="[
      'inline-flex items-center justify-center font-medium rounded-[var(--radius-control)] transition-all duration-150 ease-out cursor-pointer select-none active:scale-[0.97] disabled:opacity-50 disabled:cursor-not-allowed disabled:active:scale-100 whitespace-nowrap',
      block ? 'w-full flex' : '',
      sizeClasses,
      variantClasses,
      customClass
    ]"
    @click="$emit('click', $event)"
  >
    <svg
      v-if="loading"
      class="animate-spin -ml-0.5 h-4 w-4 text-current shrink-0"
      xmlns="http://www.w3.org/2000/svg"
      fill="none"
      viewBox="0 0 24 24"
    >
      <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
      <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
    </svg>
    <slot name="prefix" />
    <slot />
    <slot name="suffix" />
  </button>
</template>

<script setup>
import { computed } from 'vue';

const props = defineProps({
  variant: {
    type: String,
    default: 'primary' // 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger'
  },
  size: {
    type: String,
    default: 'md' // 'sm' | 'md' | 'lg'
  },
  type: {
    type: String,
    default: 'button'
  },
  block: {
    type: Boolean,
    default: false
  },
  disabled: {
    type: Boolean,
    default: false
  },
  loading: {
    type: Boolean,
    default: false
  },
  customClass: {
    type: String,
    default: ''
  }
});

defineEmits(['click']);

const sizeClasses = computed(() => {
  switch (props.size) {
    case 'sm': return 'text-[13px] px-3 py-1.5 gap-1.5 min-h-[34px]';
    case 'lg': return 'text-[15px] font-semibold px-6 py-2.5 gap-2 min-h-[46px]';
    default: return 'text-sm px-4 py-2 gap-2 min-h-[40px]';
  }
});

const variantClasses = computed(() => {
  switch (props.variant) {
    case 'secondary':
      return 'bg-[var(--bg-subtle)] hover:bg-[var(--bg-hover)] text-[var(--ink-primary)] border border-[var(--border-hairline)]';
    case 'outline':
      return 'border border-[var(--border-strong)] bg-[var(--bg-surface)] hover:bg-[var(--bg-subtle)] text-[var(--ink-primary)]';
    case 'ghost':
      return 'bg-transparent hover:bg-[var(--bg-hover)] text-[var(--ink-secondary)] hover:text-[var(--ink-primary)]';
    case 'danger':
      return 'bg-[var(--sem-danger)] hover:opacity-90 text-white';
    default: // primary
      return 'bg-[var(--accent)] hover:bg-[var(--accent-hover)] text-[var(--accent-ink)]';
  }
});
</script>