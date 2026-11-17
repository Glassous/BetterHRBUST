<template>
  <Teleport to="body">
    <div
      ref="regionRef"
      popover="manual"
      role="region"
      aria-label="系统通知"
      class="toast-region fixed bottom-5 right-5 top-auto left-auto m-0 border-0 p-0 bg-transparent z-50 flex flex-col gap-2 pointer-events-none max-w-sm w-[calc(100vw-2.5rem)] sm:w-full overflow-visible"
    >
      <div class="visually-hidden" aria-live="polite" aria-atomic="true">
        {{ announcement }}
      </div>

      <TransitionGroup
        enter-active-class="transition duration-250 ease-out"
        enter-from-class="transform translate-y-2 scale-95 opacity-0"
        enter-to-class="transform translate-y-0 scale-100 opacity-100"
        leave-active-class="transition duration-150 ease-in"
        leave-from-class="opacity-100 scale-100"
        leave-to-class="opacity-0 scale-95"
      >
        <div
          v-for="(toast, index) in toasts"
          :key="toast.id"
          class="pointer-events-auto px-4 py-3.5 rounded-[var(--shape-md)] shadow-[var(--shadow-float)] flex items-start gap-3 bg-[var(--md-inverse-surface)] text-[var(--md-inverse-on-surface)]"
          @pointerenter="pauseToast(toast.id)"
          @pointerleave="resumeToast(toast.id)"
          @focusin="pauseToast(toast.id)"
          @focusout="onFocusOut(toast.id, $event)"
        >
          <span
            :class="[
              'w-2 h-2 rounded-full mt-1.5 shrink-0',
              toast.type === 'success' ? 'bg-[var(--sem-success)]' :
              toast.type === 'danger' ? 'bg-[var(--sem-danger)]' :
              toast.type === 'warning' ? 'bg-[var(--sem-warning)]' : 'bg-[var(--md-primary)]'
            ]"
            aria-hidden="true"
          />
          <div class="flex-1 min-w-0">
            <h4 class="text-[13px] font-semibold tracking-tight">{{ toast.title }}</h4>
            <p v-if="toast.message" class="text-[13px] opacity-80 mt-0.5 leading-relaxed">{{ toast.message }}</p>
          </div>
          <button
            :ref="el => setCloseRef(toast.id, el)"
            type="button"
            aria-label="关闭通知"
            class="opacity-60 hover:opacity-100 rounded-full p-1 cursor-pointer transition-opacity"
            @click="dismissToast(toast.id, index)"
          >
            <Icon name="x" customClass="w-3.5 h-3.5" />
          </button>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import Icon from '@/components/icons/Icon.vue';
import { useToast } from '@/composables/useToast.js';

const { toasts, announcement, removeToast, pauseToast, resumeToast } = useToast();
const regionRef = ref(null);
const closeButtons = new Map();
let hideTimer = 0;

function setCloseRef(id, el) {
  if (el) closeButtons.set(id, el);
  else closeButtons.delete(id);
}

function onFocusOut(id, event) {
  const current = event.currentTarget;
  if (current && event.relatedTarget && current.contains(event.relatedTarget)) return;
  resumeToast(id);
}

async function dismissToast(id, index) {
  const activeWasInside = typeof document !== 'undefined' && closeButtons.get(id) === document.activeElement;
  const remaining = toasts.value.filter(t => t.id !== id);
  const nextTarget = remaining[Math.min(index, remaining.length - 1)];
  removeToast(id);
  if (activeWasInside && nextTarget) {
    await nextTick();
    closeButtons.get(nextTarget.id)?.focus();
  }
}

watch(
  () => toasts.value.length,
  async (len, prev = 0) => {
    const region = regionRef.value;
    if (!region || typeof region.showPopover !== 'function') return;
    clearTimeout(hideTimer);
    if (len > 0) {
      await nextTick();
      // 新通知到来时重新推至顶层，确保盖在打开的原生 <dialog> 之上
      if (region.matches(':popover-open') && len > prev) {
        try { region.hidePopover(); } catch {}
      }
      if (!region.matches(':popover-open')) {
        try { region.showPopover(); } catch {}
      }
    } else if (region.matches(':popover-open')) {
      hideTimer = window.setTimeout(() => {
        if (toasts.value.length === 0 && region.matches(':popover-open')) {
          try { region.hidePopover(); } catch {}
        }
      }, 200);
    }
  },
  { immediate: true }
);

onBeforeUnmount(() => {
  clearTimeout(hideTimer);
});
</script>

<style scoped>
.toast-region:popover-open {
  display: flex;
}

.toast-region:not(:popover-open) {
  display: none;
}

@supports not selector(:popover-open) {
  .toast-region {
    display: flex;
  }
}
</style>
