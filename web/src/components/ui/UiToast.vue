<template>
  <Teleport to="body">
    <div class="fixed bottom-5 right-5 z-50 flex flex-col gap-2 pointer-events-none max-w-sm w-[calc(100vw-2.5rem)] sm:w-full">
      <TransitionGroup
        enter-active-class="transition duration-220 ease-out"
        enter-from-class="transform translate-y-2 scale-95 opacity-0"
        enter-to-class="transform translate-y-0 scale-100 opacity-100"
        leave-active-class="transition duration-150 ease-in"
        leave-from-class="opacity-100 scale-100"
        leave-to-class="opacity-0 scale-95"
      >
        <div
          v-for="toast in toasts"
          :key="toast.id"
          class="pointer-events-auto p-4 rounded-[var(--radius-panel)] border border-[var(--border-strong)] shadow-[var(--shadow-float)] flex items-start gap-3 bg-[var(--bg-surface)] text-[var(--ink-primary)]"
        >
          <span
            :class="[
              'w-2 h-2 rounded-full mt-1.5 shrink-0',
              toast.type === 'success' ? 'bg-[var(--sem-success)]' :
              toast.type === 'danger' ? 'bg-[var(--sem-danger)]' :
              toast.type === 'warning' ? 'bg-[var(--sem-warning)]' : 'bg-[var(--accent)]'
            ]"
          />
          <div class="flex-1 min-w-0">
            <h4 class="text-[13px] font-semibold tracking-tight text-[var(--ink-primary)]">{{ toast.title }}</h4>
            <p v-if="toast.message" class="text-[13px] text-[var(--ink-secondary)] mt-0.5 leading-relaxed">{{ toast.message }}</p>
          </div>
          <button
            type="button"
            aria-label="关闭通知"
            class="text-[var(--ink-muted)] hover:text-[var(--ink-primary)] hover:bg-[var(--bg-hover)] rounded p-1 cursor-pointer transition-colors"
            @click="removeToast(toast.id)"
          >
            <Icon name="x" customClass="w-3.5 h-3.5" />
          </button>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<script setup>
import Icon from '@/components/icons/Icon.vue';
import { useToast } from '@/composables/useToast.js';
const { toasts, removeToast } = useToast();
</script>