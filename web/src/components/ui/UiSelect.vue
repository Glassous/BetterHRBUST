<template>
  <div ref="rootRef" class="relative inline-block" :class="customClass" @keydown="onKeydown">
    <button
      type="button"
      :disabled="disabled"
      :aria-label="ariaLabel || placeholder"
      aria-haspopup="listbox"
      :aria-expanded="open"
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
        customClass="w-3.5 h-3.5 text-[var(--ink-muted)] transition-transform duration-150 shrink-0"
        :class="open ? 'rotate-180' : ''"
      />
    </button>

    <Transition
      enter-active-class="transition duration-160 ease-out"
      enter-from-class="opacity-0 -translate-y-1 scale-95"
      enter-to-class="opacity-100 translate-y-0 scale-100"
      leave-active-class="transition duration-110 ease-in"
      leave-from-class="opacity-100 translate-y-0 scale-100"
      leave-to-class="opacity-0 -translate-y-1 scale-95"
    >
      <div
        v-if="open"
        role="listbox"
        class="absolute left-0 top-[calc(100%+6px)] z-50 min-w-full w-max max-w-[18rem] overflow-hidden rounded-[var(--radius-panel)] border border-[var(--border-strong)] bg-[var(--bg-surface)] shadow-[var(--shadow-float)]"
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
              'w-full flex items-center justify-between gap-2 rounded-[var(--radius-badge)] px-2.5 py-2 text-left cursor-pointer transition-colors duration-100',
              sizeClass.option,
              item.value === modelValue
                ? 'bg-[var(--accent-soft)] text-[var(--accent)] font-semibold'
                : highlightIndex === idx
                  ? 'bg-[var(--bg-hover)] text-[var(--ink-primary)]'
                  : 'text-[var(--ink-secondary)]'
            ]"
            @mouseenter="highlightIndex = idx"
            @click="choose(item)"
          >
            <span class="truncate">{{ item.label }}</span>
            <Icon
              v-if="item.value === modelValue"
              name="check"
              customClass="w-3.5 h-3.5 text-[var(--accent)] shrink-0"
            />
          </button>

          <div v-if="items.length === 0" class="px-2.5 py-2 text-[13px] text-[var(--ink-muted)]">
            暂无可选项
          </div>
        </UiScrollArea>
      </div>
    </Transition>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import Icon from '@/components/icons/Icon.vue';
import UiScrollArea from '@/components/ui/UiScrollArea.vue';

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

// 尺寸变体：sm 用于紧凑筛选条，lg 与页面输入框等高
const SIZE_CLASS = {
  sm: { trigger: 'text-[13px] py-1.5 pl-3.5 pr-3 min-h-[36px] rounded-[var(--radius-control)]', option: 'text-[13px]' },
  md: { trigger: 'text-sm py-2.5 pl-3.5 pr-3 min-h-[42px] rounded-[var(--radius-control)]', option: 'text-sm' },
  lg: { trigger: 'text-sm py-2.5 pl-3.5 pr-3 min-h-[44px] rounded-[var(--radius-control)]', option: 'text-sm' }
};

const sizeClass = computed(() => SIZE_CLASS[props.size] || SIZE_CLASS.md);
const idleClass = computed(() =>
  props.plain
    ? 'bg-[var(--bg-surface)] border-[var(--border-hairline)] hover:border-[var(--border-strong)]'
    : 'bg-[var(--bg-subtle)] border-[var(--border-hairline)] hover:border-[var(--border-strong)]'
);
const openClass = computed(() =>
  'bg-[var(--bg-surface)] border-[var(--accent)] ring-2 ring-[var(--accent-soft)]'
);

const rootRef = ref(null);
const optionRefs = ref([]);
const open = ref(false);
const highlightIndex = ref(0);

const selectedIndex = computed(() => props.items.findIndex(item => item.value === props.modelValue));
const selectedLabel = computed(() => {
  const item = props.items.find(i => i.value === props.modelValue);
  return item ? item.label : '';
});

function syncHighlight() {
  highlightIndex.value = selectedIndex.value >= 0 ? selectedIndex.value : 0;
}

/** 键盘高亮项滚动到可视区域（选项较多时如周次列表） */
function scrollHighlightIntoView() {
  const el = optionRefs.value[highlightIndex.value];
  if (el && typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'nearest' });
}

async function openPanel() {
  if (props.disabled) return;
  syncHighlight();
  open.value = true;
  await nextTick();
  scrollHighlightIntoView();
}

function closePanel() {
  open.value = false;
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

// 键盘上下移动时保持高亮项可见
watch(highlightIndex, () => {
  if (open.value) scrollHighlightIntoView();
});

// 选项变化（数据刷新 / 账号切换）时保持高亮下标有效
watch(
  () => props.items,
  () => {
    if (open.value) syncHighlight();
  }
);

onMounted(() => document.addEventListener('pointerdown', onDocumentPointerDown, true));
onBeforeUnmount(() => document.removeEventListener('pointerdown', onDocumentPointerDown, true));
</script>
