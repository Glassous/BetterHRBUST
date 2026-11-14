<template>
  <button
    type="button"
    role="switch"
    :aria-checked="modelValue"
    :class="[
      'relative inline-flex shrink-0 cursor-pointer rounded-full transition-colors duration-200 ease-in-out focus-visible:ring-2 focus-visible:ring-[var(--accent)]',
      size === 'sm' ? 'h-5 w-9' : 'h-6 w-11',
      modelValue ? 'bg-[var(--accent)]' : 'bg-[var(--border-strong)]'
    ]"
    @click="$emit('update:modelValue', !modelValue)"
  >
    <span
      :class="[
        'pointer-events-none inline-block transform rounded-full shadow-xs ring-0 transition duration-200 ease-in-out',
        modelValue ? 'bg-[var(--accent-ink)]' : 'bg-[var(--bg-surface)]',
        size === 'sm' ? 'h-4 w-4 mt-0.5' : 'h-5 w-5 mt-0.5',
        modelValue
          ? (size === 'sm' ? 'translate-x-4 ml-0.5' : 'translate-x-5 ml-0.5')
          : 'translate-x-0.5'
      ]"
    />
  </button>
</template>

<script setup>
defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  size: {
    type: String,
    default: 'md'
  }
});

defineEmits(['update:modelValue']);
</script>