<template>
  <dialog
    ref="dialogRef"
    closedby="any"
    :aria-labelledby="title ? titleId : undefined"
    :aria-label="!title ? '对话框' : undefined"
    :class="[
      'ui-native-dialog ui-native-modal m-auto p-0 border-0 w-[calc(100%-2rem)] max-h-[calc(100dvh-2rem)] rounded-[var(--shape-xxl)] bg-[var(--md-surface-container-high)] text-[var(--ink-primary)] shadow-[var(--shadow-float)] overflow-hidden',
      maxWidthClass
    ]"
    @cancel="onCancel"
    @close="onClose"
    @pointerdown="onPointerDown"
    @pointerup="onPointerUp"
  >
    <div v-if="rendered" class="flex flex-col max-h-[calc(100dvh-2rem)]">
      <div class="px-6 pt-5 pb-2 flex items-center justify-between gap-3 shrink-0">
        <h3 :id="titleId" class="text-[18px] leading-7 font-semibold tracking-tight truncate">{{ title }}</h3>
        <button
          type="button"
          aria-label="关闭弹窗"
          class="md-icon-btn w-9 h-9 cursor-pointer shrink-0"
          @click="requestClose"
        >
          <Icon name="x" customClass="w-4 h-4" />
        </button>
      </div>
      <div v-overlay-scroll class="px-6 pb-6 pt-2 max-h-[75vh] overflow-y-auto text-[var(--ink-primary)]">
        <slot />
      </div>
      <div
        v-if="$slots.footer"
        class="px-6 pb-5 flex justify-end gap-2 shrink-0"
      >
        <slot name="footer" />
      </div>
    </div>
  </dialog>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, useId, watch } from 'vue';
import Icon from '@/components/icons/Icon.vue';
import { closeModalDialog, isPointerOutsideElement } from '@/utils/topLayer.js';

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  title: {
    type: String,
    default: ''
  },
  maxWidth: {
    type: String,
    default: 'md' // 'sm' | 'md' | 'lg' | 'xl'
  }
});

const emit = defineEmits(['update:modelValue']);

const dialogRef = ref(null);
const rendered = ref(props.modelValue);
const titleId = useId();
let pressedOutside = false;

const maxWidthClass = computed(() => {
  switch (props.maxWidth) {
    case 'sm': return 'max-w-sm';
    case 'lg': return 'max-w-2xl';
    case 'xl': return 'max-w-4xl';
    default: return 'max-w-lg';
  }
});

async function openDialog() {
  rendered.value = true;
  await nextTick();
  const dialog = dialogRef.value;
  if (!dialog) return;
  dialog.removeAttribute('data-closing');
  if (!dialog.open) {
    dialog.showModal();
  }
}

async function closeDialog() {
  const dialog = dialogRef.value;
  if (!dialog || !dialog.open) {
    rendered.value = false;
    return;
  }
  await closeModalDialog(dialog);
}

function requestClose() {
  emit('update:modelValue', false);
}

function onCancel(event) {
  if (!event.cancelable) return;
  event.preventDefault();
  requestClose();
}

function onClose() {
  rendered.value = false;
  if (props.modelValue) {
    emit('update:modelValue', false);
  }
}

// closedby="any" 尚未被 Safari 支持时的渐进回退（需按下与抬起均落在弹窗外）
function onPointerDown(event) {
  const dialog = dialogRef.value;
  if (!dialog || 'closedBy' in HTMLDialogElement.prototype) return;
  pressedOutside = isPointerOutsideElement(event, dialog);
}

function onPointerUp(event) {
  const dialog = dialogRef.value;
  if (!dialog || 'closedBy' in HTMLDialogElement.prototype) return;
  const outside = isPointerOutsideElement(event, dialog);
  if (pressedOutside && outside) {
    requestClose();
  }
  pressedOutside = false;
}

watch(
  () => props.modelValue,
  visible => {
    if (visible) openDialog();
    else closeDialog();
  }
);

onMounted(() => {
  if (props.modelValue) openDialog();
});
</script>

<style scoped>
dialog.ui-native-modal {
  opacity: 0;
  transition-property: opacity, transform, display, overlay;
  transition-duration: 240ms;
  transition-timing-function: var(--ease-emphasized);
  transition-behavior: allow-discrete;
}

@media (prefers-reduced-motion: no-preference) {
  dialog.ui-native-modal {
    transform: translateY(10px) scale(0.96);
  }
}

dialog.ui-native-modal[open]:not([data-closing]) {
  opacity: 1;
}

@starting-style {
  dialog.ui-native-modal[open]:not([data-closing]) {
    opacity: 0;
  }
}

@media (prefers-reduced-motion: no-preference) {
  dialog.ui-native-modal[open]:not([data-closing]) {
    transform: translateY(0) scale(1);
  }

  @starting-style {
    dialog.ui-native-modal[open]:not([data-closing]) {
      transform: translateY(10px) scale(0.96);
    }
  }
}

dialog.ui-native-modal::backdrop {
  background-color: transparent;
  backdrop-filter: blur(0px);
  transition-property: background-color, backdrop-filter, display, overlay;
  transition-duration: 200ms;
  transition-timing-function: ease-out;
  transition-behavior: allow-discrete;
}

dialog.ui-native-modal[open]:not([data-closing])::backdrop {
  background-color: var(--bg-backdrop);
  backdrop-filter: blur(2px);
}

@starting-style {
  dialog.ui-native-modal[open]:not([data-closing])::backdrop {
    background-color: transparent;
    backdrop-filter: blur(0px);
  }
}

@media (prefers-reduced-motion: reduce) {
  dialog.ui-native-modal,
  dialog.ui-native-modal::backdrop {
    transition-duration: 80ms;
  }
}
</style>
