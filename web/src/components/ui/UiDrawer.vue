<template>
  <Teleport to="body">
    <Transition
      enter-active-class="transition duration-200 ease-out"
      enter-from-class="opacity-0"
      enter-to-class="opacity-100"
      leave-active-class="transition duration-150 ease-in"
      leave-from-class="opacity-100"
      leave-to-class="opacity-0"
    >
      <div
        v-if="modelValue"
        class="fixed inset-0 z-50 bg-[var(--bg-backdrop)] backdrop-blur-[2px] flex justify-end"
        role="dialog"
        aria-modal="true"
        :aria-label="title || '详情抽屉'"
        @click.self="$emit('update:modelValue', false)"
      >
        <div
          class="drawer-panel-enter w-full max-w-md h-full bg-[var(--bg-surface)] border-l border-[var(--border-strong)] shadow-[var(--shadow-float)] flex flex-col"
        >
          <div class="px-5 py-4 border-b border-[var(--border-hairline)] flex items-center justify-between gap-3">
            <h3 class="text-base font-semibold text-[var(--ink-primary)] tracking-tight truncate">{{ title }}</h3>
            <button
              type="button"
              aria-label="关闭抽屉"
              class="w-8 h-8 rounded-[var(--radius-control)] text-[var(--ink-muted)] hover:text-[var(--ink-primary)] hover:bg-[var(--bg-hover)] cursor-pointer transition-colors flex items-center justify-center shrink-0"
              @click="$emit('update:modelValue', false)"
            >
              <Icon name="x" customClass="w-4 h-4" />
            </button>
          </div>
          <div class="flex-1 p-5 overflow-y-auto text-[var(--ink-primary)]">
            <slot />
          </div>
          <div v-if="$slots.footer" class="p-4 border-t border-[var(--border-hairline)] bg-[var(--bg-subtle)]/55">
            <slot name="footer" />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { onBeforeUnmount, watch } from 'vue';
import Icon from '@/components/icons/Icon.vue';

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  title: {
    type: String,
    default: ''
  }
});

const emit = defineEmits(['update:modelValue']);

let lastFocused = null;
function onKeydown(event) {
  if (event.key === 'Escape' && props.modelValue) {
    emit('update:modelValue', false);
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (typeof document === 'undefined') return;
    if (visible) {
      lastFocused = document.activeElement;
      document.addEventListener('keydown', onKeydown);
    } else {
      document.removeEventListener('keydown', onKeydown);
      if (lastFocused && typeof lastFocused.focus === 'function') {
        lastFocused.focus();
      }
      lastFocused = null;
    }
  }
);

onBeforeUnmount(() => {
  if (typeof document !== 'undefined') document.removeEventListener('keydown', onKeydown);
});
</script>