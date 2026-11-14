<template>
  <div
    :class="[
      'inline-flex p-1 rounded-[var(--radius-control)] bg-[var(--bg-subtle)] border border-[var(--border-hairline)] overflow-x-auto max-w-full gap-0.5',
      customClass
    ]"
  >
    <button
      v-for="tab in items"
      :key="tab.value"
      type="button"
      :class="[
        'px-3.5 py-1.5 text-[13px] font-medium rounded-[var(--radius-badge)] transition-all duration-150 ease-out cursor-pointer select-none active:scale-[0.97] flex items-center gap-1.5 whitespace-nowrap',
        modelValue === tab.value
          ? 'bg-[var(--bg-surface)] text-[var(--ink-primary)] border border-[var(--border-hairline)] font-semibold'
          : 'text-[var(--ink-secondary)] hover:text-[var(--ink-primary)] border border-transparent'
      ]"
      @click="$emit('update:modelValue', tab.value)"
    >
      <span v-if="tab.badge" class="px-1.5 py-0.5 rounded-[var(--radius-badge)] text-xs leading-none bg-[var(--bg-subtle)] text-[var(--ink-secondary)]">
        {{ tab.badge }}
      </span>
      {{ tab.label }}
    </button>
  </div>
</template>

<script setup>
defineProps({
  items: {
    type: Array,
    required: true // [{ label: '大节', value: 'combine', badge?: 2 }]
  },
  modelValue: {
    type: [String, Number],
    required: true
  },
  customClass: {
    type: String,
    default: ''
  }
});

defineEmits(['update:modelValue']);
</script>