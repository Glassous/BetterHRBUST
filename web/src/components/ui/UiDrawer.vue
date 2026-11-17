<template>
  <dialog
    ref="dialogRef"
    closedby="any"
    :aria-labelledby="title ? titleId : undefined"
    :aria-label="!title ? '详情抽屉' : undefined"
    class="ui-native-dialog ui-native-drawer fixed inset-y-0 right-0 left-auto m-0 p-0 border-0 w-full max-w-md h-dvh max-h-none bg-[var(--md-surface-container-low)] text-[var(--ink-primary)] shadow-[var(--shadow-float)] rounded-l-[var(--shape-xxl)] overflow-hidden"
    @cancel="onCancel"
    @close="onClose"
    @pointerdown="onPointerDown"
    @pointerup="onPointerUp"
  >
    <div v-if="rendered" class="h-full flex flex-col">
      <div class="px-6 pt-5 pb-3 flex items-center justify-between gap-3 shrink-0">
        <h3 :id="titleId" class="text-[18px] leading-7 font-semibold tracking-tight truncate">{{ title }}</h3>
        <button
          type="button"
          aria-label="关闭抽屉"
          class="md-icon-btn w-9 h-9 cursor-pointer shrink-0"
          @click="requestClose"
        >
          <Icon name="x" customClass="w-4 h-4" />
        </button>
      </div>
      <div v-overlay-scroll class="flex-1 px-6 pb-5 overflow-y-auto text-[var(--ink-primary)]">
        <slot />
      </div>
      <div
        v-if="$slots.footer"
        class="px-6 py-4 border-t border-[var(--border-hairline)] bg-[var(--md-surface-container)]/50 shrink-0"
      >
        <slot name="footer" />
      </div>
    </div>
  </dialog>
</template>

<script setup>
import { nextTick, onMounted, ref, useId, watch } from 'vue';
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
  }
});

const emit = defineEmits(['update:modelValue']);

const dialogRef = ref(null);
const rendered = ref(props.modelValue);
const titleId = useId();
let pressedOutside = false;

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
dialog.ui-native-drawer {
  opacity: 0;
  transition-property: opacity, transform, display, overlay;
  transition-duration: 280ms;
  transition-timing-function: var(--ease-emphasized-decelerate);
  transition-behavior: allow-discrete;
}

@media (prefers-reduced-motion: no-preference) {
  dialog.ui-native-drawer {
    transform: translateX(28px);
  }
}

dialog.ui-native-drawer[open]:not([data-closing]) {
  opacity: 1;
}

@starting-style {
  dialog.ui-native-drawer[open]:not([data-closing]) {
    opacity: 0;
  }
}

@media (prefers-reduced-motion: no-preference) {
  dialog.ui-native-drawer[open]:not([data-closing]) {
    transform: translateX(0);
  }

  @starting-style {
    dialog.ui-native-drawer[open]:not([data-closing]) {
      transform: translateX(28px);
    }
  }
}

dialog.ui-native-drawer::backdrop {
  background-color: transparent;
  backdrop-filter: blur(0px);
  transition-property: background-color, backdrop-filter, display, overlay;
  transition-duration: 220ms;
  transition-timing-function: ease-out;
  transition-behavior: allow-discrete;
}

dialog.ui-native-drawer[open]:not([data-closing])::backdrop {
  background-color: var(--bg-backdrop);
  backdrop-filter: blur(2px);
}

@starting-style {
  dialog.ui-native-drawer[open]:not([data-closing])::backdrop {
    background-color: transparent;
    backdrop-filter: blur(0px);
  }
}

@media (prefers-reduced-motion: reduce) {
  dialog.ui-native-drawer,
  dialog.ui-native-drawer::backdrop {
    transition-duration: 80ms;
  }
}
</style>
