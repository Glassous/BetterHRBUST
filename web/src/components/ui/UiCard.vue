<template>
  <div
    :class="[
      'rounded-[var(--radius-card)] border border-[var(--border-hairline)] bg-[var(--bg-surface)]',
      hoverable
        ? 'transition-all duration-200 ease-out hover:-translate-y-0.5 hover:border-[var(--border-strong)]'
        : 'transition-colors duration-200',
      customClass
    ]"
  >
    <div
      v-if="$slots.header || title || $slots['header-action']"
      class="px-5 sm:px-6 py-4 border-b border-[var(--border-hairline)] flex items-center justify-between gap-3"
    >
      <slot name="header">
        <div class="flex items-center gap-2.5 min-w-0">
          <slot name="title-prefix" />
          <div v-if="title" class="min-w-0">
            <h3 class="text-base font-semibold text-[var(--ink-primary)] tracking-tight truncate">{{ title }}</h3>
            <p v-if="subtitle" class="text-[13px] text-[var(--ink-muted)] mt-0.5 truncate">{{ subtitle }}</p>
          </div>
        </div>
        <div class="shrink-0 flex items-center gap-2">
          <slot name="header-action" />
        </div>
      </slot>
    </div>
    <div :class="['p-5 sm:p-6', bodyClass]">
      <slot />
    </div>
    <div
      v-if="$slots.footer"
      class="px-5 sm:px-6 py-3.5 border-t border-[var(--border-hairline)] bg-[var(--bg-subtle)]/55 rounded-b-[var(--radius-card)]"
    >
      <slot name="footer" />
    </div>
  </div>
</template>

<script setup>
defineProps({
  title: {
    type: String,
    default: ''
  },
  subtitle: {
    type: String,
    default: ''
  },
  hoverable: {
    type: Boolean,
    default: false
  },
  customClass: {
    type: String,
    default: ''
  },
  bodyClass: {
    type: String,
    default: ''
  }
});
</script>