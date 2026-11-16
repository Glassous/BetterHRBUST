<template>
  <button
    :type="type"
    :disabled="disabled || loading"
    :class="[
      'md-btn md-state',
      sizeClasses,
      variantClasses,
      block ? 'w-full flex' : '',
      customClass
    ]"
    @click="$emit('click', $event)"
  >
    <!-- MD3E 加载态：细描边圆弧 + 旋转，尺寸随按钮高度 -->
    <svg
      v-if="loading"
      class="animate-spin shrink-0"
      :class="size === 'sm' ? 'h-3.5 w-3.5' : size === 'lg' ? 'h-5 w-5' : 'h-4 w-4'"
      xmlns="http://www.w3.org/2000/svg"
      fill="none"
      viewBox="0 0 24 24"
      aria-hidden="true"
    >
      <circle class="opacity-25" cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" />
      <path
        class="opacity-90"
        fill="currentColor"
        d="M21 12a9 9 0 0 0-9-9V0a12 12 0 0 1 12 12h-3Z"
      />
    </svg>
    <slot name="prefix" />
    <slot />
    <slot name="suffix" />
  </button>
</template>

<script setup>
import { computed } from 'vue';

const props = defineProps({
  variant: {
    type: String,
    default: 'primary' // 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger'
  },
  size: {
    type: String,
    default: 'md' // 'sm' | 'md' | 'lg'
  },
  type: {
    type: String,
    default: 'button'
  },
  block: {
    type: Boolean,
    default: false
  },
  disabled: {
    type: Boolean,
    default: false
  },
  loading: {
    type: Boolean,
    default: false
  },
  customClass: {
    type: String,
    default: ''
  }
});

defineEmits(['click']);

const sizeClasses = computed(() => {
  switch (props.size) {
    case 'sm': return 'text-[13px] px-4 gap-1.5 min-h-[36px]';
    case 'lg': return 'text-[15px] font-semibold px-6 gap-2 min-h-[52px]';
    default: return 'text-sm px-5 gap-2 min-h-[44px]';
  }
});

const variantClasses = computed(() => {
  switch (props.variant) {
    case 'secondary':
      return 'md-btn--tonal';
    case 'outline':
      return 'md-btn--outlined';
    case 'ghost':
      return 'md-btn--text';
    case 'danger':
      return 'md-btn--danger';
    default:
      return 'md-btn--filled';
  }
});
</script>
