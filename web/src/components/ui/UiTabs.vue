<template>
  <div
    :class="[
      'inline-flex p-1 rounded-[var(--shape-full)] bg-[var(--md-surface-container-high)] overflow-x-auto max-w-full gap-1',
      customClass
    ]"
    role="tablist"
    :aria-label="ariaLabel || undefined"
    @keydown="onKeydown"
  >
    <button
      v-for="(tab, index) in items"
      :key="tab.value"
      :ref="el => (tabRefs[index] = el)"
      type="button"
      role="tab"
      :tabindex="modelValue === tab.value ? 0 : -1"
      :aria-selected="modelValue === tab.value"
      :class="[
        'px-4 py-1.5 text-[13px] font-medium rounded-[var(--shape-full)] transition-all duration-200 cursor-pointer select-none active:scale-[0.97] flex items-center gap-1.5 whitespace-nowrap shrink-0',
        modelValue === tab.value
          ? 'bg-[var(--md-secondary-container)] text-[var(--md-on-secondary-container)] font-semibold shadow-[0_1px_2px_rgba(27,27,37,0.06)]'
          : 'text-[var(--ink-secondary)] hover:text-[var(--ink-primary)] hover:bg-[var(--md-surface-container-highest)]'
      ]"
      @click="selectTab(tab, index)"
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
import { ref } from 'vue';

const props = defineProps({
  items: {
    type: Array,
    required: true // [{ label: '大节', value: 'combine', badge?: 2 }]
  },
  modelValue: {
    type: [String, Number],
    required: true
  },
  ariaLabel: {
    type: String,
    default: ''
  },
  customClass: {
    type: String,
    default: ''
  }
});

const emit = defineEmits(['update:modelValue']);

const tabRefs = ref([]);

function selectTab(tab, index) {
  if (!tab) return;
  emit('update:modelValue', tab.value);
  tabRefs.value[index]?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
}

function onKeydown(event) {
  const count = props.items.length;
  if (count === 0) return;
  const currentIdx = Math.max(0, props.items.findIndex(item => item.value === props.modelValue));
  let nextIdx = null;
  if (event.key === 'ArrowRight') nextIdx = (currentIdx + 1) % count;
  else if (event.key === 'ArrowLeft') nextIdx = (currentIdx - 1 + count) % count;
  else if (event.key === 'Home') nextIdx = 0;
  else if (event.key === 'End') nextIdx = count - 1;
  if (nextIdx === null) return;

  event.preventDefault();
  selectTab(props.items[nextIdx], nextIdx);
  tabRefs.value[nextIdx]?.focus?.();
}
</script>
