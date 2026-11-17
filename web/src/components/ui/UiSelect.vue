<template>
  <div ref="rootRef" class="relative inline-block" :class="customClass" @keydown="onKeydown">
    <button
      ref="triggerRef"
      type="button"
      :disabled="disabled"
      :aria-label="ariaLabel || placeholder"
      aria-haspopup="listbox"
      :aria-expanded="open"
      :aria-controls="listboxId"
      :style="triggerStyle"
      :class="[
        'w-full flex items-center justify-between gap-1.5 border transition-colors duration-150 outline-none cursor-pointer select-none',
        sizeClass.trigger,
        open ? openClass : idleClass,
        disabled ? 'opacity-50 cursor-not-allowed' : ''
      ]"
      @click="togglePanel"
    >
      <span class="truncate" :class="selectedLabel ? 'text-[var(--ink-primary)]' : 'text-[var(--ink-muted)]'">
        {{ selectedLabel || placeholder }}
      </span>
      <Icon
        name="chevron-down"
        customClass="w-4 h-4 text-[var(--ink-muted)] transition-transform duration-200 shrink-0"
        :class="open ? 'rotate-180' : ''"
      />
    </button>

    <div
      :id="listboxId"
      ref="panelRef"
      :popover="supportsPopover ? 'manual' : undefined"
      role="listbox"
      :aria-label="ariaLabel || placeholder"
      :style="panelStyle"
      :class="[
        'ui-select-panel z-50 w-max max-w-[18rem] overflow-hidden rounded-[var(--shape-lg)] border border-[var(--border-hairline)] bg-[var(--md-surface-container-lowest)] shadow-[var(--shadow-float)]',
        supportsPopover ? '' : (open ? 'absolute left-0 top-[calc(100%+6px)] block' : 'hidden')
      ]"
    >
      <UiScrollArea viewport-class="p-1.5 max-h-64">
        <button
          v-for="(item, idx) in items"
          :key="String(item.value)"
          :ref="el => (optionRefs[idx] = el)"
          type="button"
          role="option"
          tabindex="-1"
          :aria-selected="item.value === modelValue"
          :class="[
            'w-full flex items-center justify-between gap-2 rounded-[var(--shape-sm)] px-3 py-2 text-left cursor-pointer transition-colors duration-100',
            sizeClass.option,
            item.value === modelValue
              ? 'bg-[var(--md-secondary-container)] text-[var(--md-on-secondary-container)] font-semibold'
              : highlightIndex === idx
                ? 'bg-[var(--md-surface-container-high)] text-[var(--ink-primary)]'
                : 'text-[var(--ink-secondary)]'
          ]"
          @mouseenter="highlightIndex = idx"
          @click="choose(item)"
        >
          <span class="truncate">{{ item.label }}</span>
          <Icon
            v-if="item.value === modelValue"
            name="check"
            customClass="w-4 h-4 shrink-0"
          />
        </button>

        <div v-if="items.length === 0" class="px-3 py-2 text-[13px] text-[var(--ink-muted)]">
          暂无可选项
        </div>
      </UiScrollArea>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue';
import Icon from '@/components/icons/Icon.vue';
import UiScrollArea from '@/components/ui/UiScrollArea.vue';
import { computeAnchoredFixedStyle, supportsAnchorPositionArea } from '@/utils/topLayer.js';

const props = defineProps({
  modelValue: {
    type: [String, Number],
    default: ''
  },
  // [{ label: '全部学期', value: 'all' }] —— 与 UiTabs 的 items 结构保持一致
  items: {
    type: Array,
    default: () => []
  },
  placeholder: {
    type: String,
    default: '请选择'
  },
  ariaLabel: {
    type: String,
    default: ''
  },
  size: {
    type: String,
    default: 'md'
  },
  // 白底朴素样式：用于与页面原生输入框（白底 p-3 圆角）并排的场景
  plain: {
    type: Boolean,
    default: false
  },
  disabled: {
    type: Boolean,
    default: false
  },
  customClass: {
    type: String,
    default: ''
  }
});

const emit = defineEmits(['update:modelValue', 'change']);

const SIZE_CLASS = {
  sm: { trigger: 'text-[13px] py-1.5 pl-4 pr-3 min-h-[36px] rounded-[var(--shape-md)]', option: 'text-[13px]' },
  md: { trigger: 'text-sm py-2.5 pl-4 pr-3 min-h-[44px] rounded-[var(--shape-md)]', option: 'text-sm' },
  lg: { trigger: 'text-sm py-2.5 pl-4 pr-3 min-h-[46px] rounded-[var(--shape-md)]', option: 'text-sm' }
};

const sizeClass = computed(() => SIZE_CLASS[props.size] || SIZE_CLASS.md);
const idleClass = computed(() =>
  props.plain
    ? 'bg-[var(--bg-surface)] border-[var(--border-hairline)] hover:border-[var(--border-strong)]'
    : 'bg-[var(--md-surface-container-high)] border-transparent hover:bg-[var(--md-surface-container-highest)]'
);
const openClass = 'bg-[var(--bg-surface)] border-[var(--md-primary)] ring-2 ring-[var(--accent-soft)]';

const uid = useId().replace(/[^a-zA-Z0-9_-]/g, '');
const listboxId = `ui-select-list-${uid}`;
const anchorName = `--ui-select-${uid}`;
const supportsPopover = typeof HTMLElement !== 'undefined' && Object.hasOwn(HTMLElement.prototype, 'popover');
const supportsAnchor = supportsAnchorPositionArea();

const rootRef = ref(null);
const triggerRef = ref(null);
const panelRef = ref(null);
const optionRefs = ref([]);
const open = ref(false);
const highlightIndex = ref(0);
const triggerWidth = ref(0);
const fallbackPosStyle = ref({});

const triggerStyle = computed(() => (supportsPopover && supportsAnchor ? { anchorName } : undefined));
const panelStyle = computed(() => {
  const base = { minWidth: triggerWidth.value ? `${triggerWidth.value}px` : undefined };
  if (!supportsPopover) return base;
  if (supportsAnchor) return { ...base, positionAnchor: anchorName };
  return { ...base, ...fallbackPosStyle.value };
});

const selectedIndex = computed(() => props.items.findIndex(item => item.value === props.modelValue));
const selectedLabel = computed(() => {
  const item = props.items.find(i => i.value === props.modelValue);
  return item ? item.label : '';
});

function syncHighlight() {
  highlightIndex.value = selectedIndex.value >= 0 ? selectedIndex.value : 0;
}

function updatePlacement() {
  const trigger = triggerRef.value;
  if (!trigger) return;
  triggerWidth.value = Math.round(trigger.getBoundingClientRect().width);
  if (supportsPopover && !supportsAnchor) {
    fallbackPosStyle.value = computeAnchoredFixedStyle(trigger, panelRef.value, 6);
  }
}

function scrollHighlightIntoView() {
  const el = optionRefs.value[highlightIndex.value];
  if (el && typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'nearest' });
}

async function openPanel() {
  if (props.disabled) return;
  syncHighlight();
  updatePlacement();
  open.value = true;
  await nextTick();
  const panel = panelRef.value;
  if (supportsPopover && panel && !panel.matches(':popover-open')) {
    try { panel.showPopover(); } catch {}
  }
  updatePlacement();
  scrollHighlightIntoView();
}

function closePanel() {
  open.value = false;
  const panel = panelRef.value;
  if (supportsPopover && panel && panel.matches(':popover-open')) {
    try { panel.hidePopover(); } catch {}
  }
}

function togglePanel() {
  if (open.value) closePanel();
  else openPanel();
}

function choose(item) {
  if (!item) return;
  closePanel();
  if (item.value === props.modelValue) return;
  emit('update:modelValue', item.value);
  emit('change', item.value);
}

function onKeydown(event) {
  if (props.disabled || props.items.length === 0) return;
  const count = props.items.length;
  const key = event.key;

  if (!open.value) {
    if (key === 'ArrowDown' || key === 'ArrowUp' || key === 'Enter' || key === ' ') {
      event.preventDefault();
      openPanel();
    }
    return;
  }

  if (key === 'Escape') {
    event.preventDefault();
    closePanel();
  } else if (key === 'Tab') {
    closePanel();
  } else if (key === 'Enter' || key === ' ') {
    event.preventDefault();
    choose(props.items[highlightIndex.value]);
  } else if (key === 'ArrowDown') {
    event.preventDefault();
    highlightIndex.value = (highlightIndex.value + 1) % count;
  } else if (key === 'ArrowUp') {
    event.preventDefault();
    highlightIndex.value = (highlightIndex.value - 1 + count) % count;
  } else if (key === 'Home') {
    event.preventDefault();
    highlightIndex.value = 0;
  } else if (key === 'End') {
    event.preventDefault();
    highlightIndex.value = count - 1;
  }
}

function onDocumentPointerDown(event) {
  if (!open.value) return;
  if (rootRef.value && !rootRef.value.contains(event.target)) closePanel();
}

function onViewportChange() {
  if (open.value) updatePlacement();
}

watch(highlightIndex, () => {
  if (open.value) scrollHighlightIntoView();
});

watch(
  () => props.items,
  () => {
    if (open.value) syncHighlight();
  }
);

watch(open, value => {
  if (typeof window === 'undefined') return;
  if (value) {
    window.addEventListener('resize', onViewportChange);
    window.addEventListener('scroll', onViewportChange, true);
  } else {
    window.removeEventListener('resize', onViewportChange);
    window.removeEventListener('scroll', onViewportChange, true);
  }
});

onMounted(() => document.addEventListener('pointerdown', onDocumentPointerDown, true));
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocumentPointerDown, true);
  if (typeof window !== 'undefined') {
    window.removeEventListener('resize', onViewportChange);
    window.removeEventListener('scroll', onViewportChange, true);
  }
});
</script>

<style scoped>
.ui-select-panel[popover] {
  opacity: 0;
  transition-property: opacity, transform, display, overlay;
  transition-duration: 150ms;
  transition-timing-function: ease-out;
  transition-behavior: allow-discrete;
}

.ui-select-panel[popover]:popover-open {
  opacity: 1;
}

@starting-style {
  .ui-select-panel[popover]:popover-open {
    opacity: 0;
  }
}

@media (prefers-reduced-motion: no-preference) {
  .ui-select-panel[popover] {
    transform: translateY(-4px) scale(0.97);
  }

  .ui-select-panel[popover]:popover-open {
    transform: translateY(0) scale(1);
  }

  @starting-style {
    .ui-select-panel[popover]:popover-open {
      transform: translateY(-4px) scale(0.97);
    }
  }
}

@supports (position-area: block-end) {
  .ui-select-panel[popover] {
    inset: auto;
    margin-block: 6px;
    margin-inline: 0;
    position-area: block-end span-inline-end;
    position-try-fallbacks: flip-block, flip-inline, flip-block flip-inline;
  }
}
</style>
