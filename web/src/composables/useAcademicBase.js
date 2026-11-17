/**
 * 教务地址的响应式封装
 *
 * 与 useTheme.js 同一套范式：模块级单例 + 外部事件同步，
 * 多个组件（设置页、登录页）共享同一份状态。
 */

import { ref, computed } from 'vue';
import {
  OFFICIAL_ACADEMIC_BASE,
  academicBaseState,
  resetAcademicBase,
  saveAcademicBase,
  subscribeAcademicBase
} from '@/services/academic/base.js';

const initial = academicBaseState();
const base = ref(initial.base);
const invalidReason = ref(initial.invalidReason);

let bound = false;
function bind() {
  if (bound) return;
  bound = true;
  subscribeAcademicBase((state) => {
    base.value = state.base;
    invalidReason.value = state.invalidReason;
  });
}

export function useAcademicBase() {
  bind();
  /** 当前生效地址（自定义优先，否则官方默认） */
  const effective = computed(() => base.value || OFFICIAL_ACADEMIC_BASE);
  const isOfficial = computed(() => !base.value);
  /** 地址是否被识别为非法而回退默认（只提示，不影响请求） */
  const hasInvalidStored = computed(() => !!invalidReason.value);

  return {
    base,
    effective,
    isOfficial,
    invalidReason,
    hasInvalidStored,
    officialBase: OFFICIAL_ACADEMIC_BASE,
    /** 保存并立即生效；非法输入抛错（中文），由调用方展示 */
    save: saveAcademicBase,
    /** 恢复官方默认（删除存储键） */
    reset: resetAcademicBase
  };
}
