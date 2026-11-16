<template>
  <div
    :class="[
      'rounded-[var(--shape-xl)] transition-all duration-200 ease-out overflow-hidden',
      variantClasses,
      hoverable ? 'hover:-translate-y-0.5' : '',
      customClass
    ]"
  >
    <div
      v-if="$slots.header || title || $slots['header-action']"
      :class="[
        'px-5 sm:px-6 pt-5 pb-3.5 flex items-center justify-between gap-3',
        divider ? 'border-b border-[var(--border-hairline)]' : ''
      ]"
    >
      <slot name="header">
        <div class="flex items-center gap-2.5 min-w-0">
          <slot name="title-prefix" />
          <div v-if="title" class="min-w-0">
            <h3 class="text-[15px] leading-6 font-semibold text-[var(--ink-primary)] tracking-tight truncate">{{ title }}</h3>
            <p v-if="subtitle" class="text-[12.5px] text-[var(--ink-muted)] mt-0.5 truncate">{{ subtitle }}</p>
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
      class="px-5 sm:px-6 py-3.5 border-t border-[var(--border-hairline)] bg-[var(--md-surface-container)]/45 rounded-b-[var(--shape-xl)]"
    >
      <slot name="footer" />
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';

const props = defineProps({
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
  // filled：MD3E 填充卡片；outlined：描边卡片；hero：高强调容器；plain：无容器
  variant: {
    type: String,
    default: 'filled'
  },
  divider: {
    type: Boolean,
    default: true
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

const variantClasses = computed(() => {
  switch (props.variant) {
    case 'outlined':
      return 'bg-[var(--bg-surface)] border border-[var(--border-hairline)]';
    case 'hero':
      return 'bg-[var(--md-secondary-container)] text-[var(--md-on-secondary-container)]';
    case 'plain':
      return '';
    default:
      return 'bg-[var(--md-surface-container-low)]';
  }
});
</script>
