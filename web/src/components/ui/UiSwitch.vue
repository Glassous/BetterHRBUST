<template>
  <button
    :id="id || undefined"
    type="button"
    role="switch"
    :aria-checked="modelValue"
    :aria-label="ariaLabel || undefined"
    :aria-labelledby="ariaLabelledby || undefined"
    :class="[
      'relative inline-flex shrink-0 cursor-pointer rounded-full border-2 transition-colors duration-200',
      size === 'sm' ? 'h-6 w-10' : 'h-8 w-13',
      modelValue
        ? 'bg-[var(--md-primary)] border-transparent'
        : 'bg-[var(--md-surface-container-highest)] border-[var(--md-outline)]'
    ]"
    @click="$emit('update:modelValue', !modelValue)"
  >
    <span
      aria-hidden="true"
      :class="[
        'pointer-events-none absolute top-1/2 -translate-y-1/2 rounded-full transition-all duration-200',
        size === 'sm'
          ? (modelValue ? 'h-[18px] w-[18px] left-[19px] bg-[var(--md-on-primary)]' : 'h-3 w-3 left-[5px] bg-[var(--md-outline)]')
          : (modelValue ? 'h-6 w-6 left-[23px] bg-[var(--md-on-primary)]' : 'h-4 w-4 left-[6px] bg-[var(--md-outline)]')
      ]"
    />
  </button>
</template>

<script setup>
defineProps({
  id: {
    type: String,
    default: ''
  },
  modelValue: {
    type: Boolean,
    default: false
  },
  size: {
    type: String,
    default: 'md'
  },
  ariaLabel: {
    type: String,
    default: ''
  },
  ariaLabelledby: {
    type: String,
    default: ''
  }
});

defineEmits(['update:modelValue']);
</script>
