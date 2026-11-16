<template>
  <div
    :class="[
      'inline-flex p-1 rounded-[var(--shape-full)] bg-[var(--md-surface-container-high)] overflow-x-auto max-w-full gap-1',
      customClass
    ]"
    role="tablist"
  >
    <button
      v-for="tab in items"
      :key="tab.value"
      type="button"
      role="tab"
      :aria-selected="modelValue === tab.value"
      :class="[
        'px-4 py-1.5 text-[13px] font-medium rounded-[var(--shape-full)] transition-all duration-200 cursor-pointer select-none active:scale-[0.97] flex items-center gap-1.5 whitespace-nowrap',
        modelValue === tab.value
          ? 'bg-[var(--md-secondary-container)] text-[var(--md-on-secondary-container)] font-semibold shadow-[0_1px_2px_rgba(27,27,37,0.06)]'
          : 'text-[var(--ink-secondary)] hover:text-[var(--ink-primary)] hover:bg-[var(--md-surface-container-highest)]'
      ]"
      @click="$emit('update:modelValue', tab.value)"
    >
      <span
        v-if="tab.badge"
        class="px-1.5 py-0.5 rounded-full text-[11px] leading-none bg-[var(--md-surface-container-lowest)]/70 text-[var(--ink-secondary)]"
      >
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
