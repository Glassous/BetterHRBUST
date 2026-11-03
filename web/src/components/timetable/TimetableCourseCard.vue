<template>
  <!--
    课程卡片：小卡与大卡是**同一个** DOM 节点。
    展开时该节点被 Teleport 到 body（脱离课表网格的层叠/裁剪上下文）并切换为居中固定定位，
    由 GSAP Flip 在这个元素自身上完成 First → Last → Invert → Play，不存在两张卡片叠加。
  -->
  <Teleport to="body" :disabled="!(expanded || closing)">
    <div
      ref="cardRef"
      class="tt-card"
      :class="[
        active ? 'course-card' : COURSE_MUTED,
        { 'is-floating': expanded || closing, 'is-open': expanded }
      ]"
      :style="cardStyle"
      :role="expanded ? 'dialog' : null"
      :aria-modal="expanded ? 'true' : null"
      :aria-label="expanded ? `${course.courseName} 课程详情` : null"
      @click="onCardClick"
      @keydown.esc.stop="requestClose"
    >
      <div class="tt-top">
        <div class="tt-head">
          <div ref="titleRef" class="tt-title" :class="{ 'line-clamp-2': !expanded }">
            {{ course.courseName }}
          </div>
          <!-- 右上角正圆形关闭按钮：36px 圆 / 18px 叉号 / content 色 10% 底（Android 同款） -->
          <button
            v-if="expanded || closing"
            ref="closeRef"
            type="button"
            class="tt-close"
            aria-label="关闭"
            @click.stop="requestClose"
          >
            <Icon name="x" customClass="h-[18px] w-[18px]" />
          </button>
        </div>
        <div ref="locationRef" class="tt-location">{{ course.location || '待定' }}</div>
      </div>

      <div ref="teacherRef" class="tt-teacher">
        {{ course.teacher || '—' }}<span v-if="!expanded" class="tt-weeks"> · {{ course.weeks }}</span>
      </div>

      <!-- 明细：只有大卡才有，随展开淡入（Android 端这些信息不参与共享元素过渡） -->
      <div v-if="expanded || closing" ref="factsRef" class="tt-facts">
        <div class="tt-divider"></div>
        <div v-for="row in rows" :key="row.label" class="tt-row">
          <span class="tt-row-label">{{ row.label }}</span>
          <span class="tt-row-value">{{ row.value }}</span>
        </div>
      </div>
    </div>
  </Teleport>

  <!-- 遮罩：独立 Teleport，退场动画期间仍需留在 body 内做淡出 -->
  <Teleport to="body">
    <div
      v-if="expanded || closing"
      ref="scrimRef"
      class="tt-scrim"
      :style="{ backgroundColor: scrimColor }"
      @click="requestClose"
      @wheel.prevent.self
    ></div>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref } from 'vue';
import { gsap, Flip } from '@/utils/gsapFlip.js';
import Icon from '@/components/icons/Icon.vue';
import { useTheme } from '@/composables/useTheme.js';
import { COURSE_MUTED, getCourseColor } from '@/utils/courseColors.js';
import { BASE_SLOT_TIMES, COMBINE_SLOT_TIMES } from '@/utils/periodTimes.js';

const props = defineProps({
  course: { type: Object, required: true },
  /** 是否为当前教学周课程，false 时走中性灰配色 */
  active: { type: Boolean, default: true },
  /** 是否处于展开（大卡）态，由父组件以「当前选中的课程」驱动 */
  expanded: { type: Boolean, default: false },
  /** 大节 / 小节模式，决定「节次安排」文案 */
  viewMode: { type: String, default: 'combine' }
});

const emit = defineEmits(['open', 'close']);

const { isDark } = useTheme();

const cardRef = ref(null);
const scrimRef = ref(null);
const titleRef = ref(null);
const locationRef = ref(null);
const teacherRef = ref(null);
const factsRef = ref(null);
const closeRef = ref(null);

/** Flip 参与插值的样式：内边距与圆角随几何一起连续变化 */
const FLIP_PROPS = 'paddingTop,paddingBottom,paddingLeft,paddingRight,borderRadius';
const OPEN_DURATION = 0.52;
const OPEN_EASE = 'power3.out';
const CLOSE_DURATION = 0.36;
const CLOSE_EASE = 'power2.in';

/**
 * 小卡字号（与 .tt-card 的 CSS 一致）与大卡字号（与 .is-open 的 CSS 一致）。
 *
 * 行高一律用 px：GSAP 在 `1.25` 与 `28px` 之间插值会得到无单位/px 混杂的中间值，
 * 折叠时字行会被压扁，因此两端必须同单位。
 */
const MINI_TYPE = { title: [14, '18px'], sub: [12, '16px'], teacher: [11, '14px'] };
const OPEN_TYPE = { title: [22, '28px'], sub: [15, '20px'], teacher: [15, '20px'] };

const closing = ref(false);
/** 展开前的 First 状态（点击瞬间从小卡上量取） */
let pendingState = null;
/** 小卡在网格中的位置：退场时作为归位目标 */
let homeRect = null;
let timeline = null;
let lastFocused = null;

const reduceMotion = () =>
  typeof window !== 'undefined' && window.matchMedia
    ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
    : false;

const cardStyle = computed(() => {
  const base = props.active ? getCourseColor(props.course).style : null;
  if (!props.expanded) return base;
  // 大卡投影用行内样式给出（优先级最高，避免被 .dark .course-card 的 box-shadow 覆盖）
  return {
    ...(base || {}),
    boxShadow: '0 28px 60px -12px rgb(0 0 0 / 0.35), 0 8px 20px -8px rgb(0 0 0 / 0.16)'
  };
});

const scrimColor = computed(() => (isDark.value ? 'rgba(0, 0, 0, 0.55)' : 'rgba(0, 0, 0, 0.35)'));

/** 节次安排文案：与 Android 端详情卡片一致（选课文案 · 第 N 大节/节 起止时间） */
const sectionText = computed(() => {
  const course = props.course;
  const slots = props.viewMode === 'combine' ? COMBINE_SLOT_TIMES : BASE_SLOT_TIMES;
  const slot = slots[course.sectionIndex - 1];
  const parts = [];
  if (course.sectionLabel) parts.push(course.sectionLabel);
  if (slot) {
    parts.push(
      props.viewMode === 'combine'
        ? `第 ${course.sectionIndex} 大节 ${slot.time}`
        : `第 ${course.sectionIndex} 节 ${slot.time}`
    );
  }
  return parts.join(' · ');
});

const rows = computed(() => {
  const course = props.course;
  const list = [
    { label: '上课周次', value: course.weeks || '—' },
    { label: '节次安排', value: sectionText.value || '—' }
  ];
  if (course.hoursType) list.push({ label: '学时类型', value: course.hoursType });
  if (course.courseSeq) list.push({ label: '课程序号', value: course.courseSeq });
  return list;
});

function setType(sizes) {
  gsap.set(titleRef.value, { fontSize: sizes.title[0], lineHeight: sizes.title[1] });
  gsap.set(locationRef.value, { fontSize: sizes.sub[0], lineHeight: sizes.sub[1] });
  gsap.set(teacherRef.value, { fontSize: sizes.teacher[0], lineHeight: sizes.teacher[1] });
}

/**
 * 大卡几何：宽度/高度由 CSS 决定，量出实际尺寸后把左上角固定在屏幕中央。
 *
 * 左上角必须固定 —— 否则宽度动画会让元素不断重新居中，与 Flip 的位移叠加成漂移。
 */
function pinCentered(el) {
  el.style.left = '0px';
  el.style.top = '0px';
  const rect = el.getBoundingClientRect();
  el.style.left = `${Math.round((window.innerWidth - rect.width) / 2)}px`;
  el.style.top = `${Math.round((window.innerHeight - rect.height) / 2)}px`;
}

function onCardClick() {
  if (props.expanded || closing.value) return;
  const el = cardRef.value;
  if (!el) return;
  const rect = el.getBoundingClientRect();
  homeRect = { left: rect.left, top: rect.top, width: rect.width, height: rect.height };
  // ① First：小卡此刻的真实位置 / 尺寸 / 内边距 / 圆角
  pendingState = Flip.getState(el, { props: FLIP_PROPS });
  lastFocused = document.activeElement;
  emit('open', props.course);
  nextTick(playOpen);
}

function playOpen() {
  const el = cardRef.value;
  const scrim = scrimRef.value;
  if (!el || !scrim) return;

  // ① 先把大卡摆到最终位置（此刻仍是终态字号，高度 = 动画落点高度）
  pinCentered(el);
  gsap.set(factsRef.value, { autoAlpha: 0, y: 6 });
  gsap.set(closeRef.value, { autoAlpha: 0, scale: 0.7 });
  gsap.set(scrim, { opacity: 0 });

  const showAll = () => {
    // 收尾：把 FLIP 期间临时放开的最小高度交还 CSS（Last 状态就是按 min-height 量出来的，不会跳）
    if (cardRef.value) cardRef.value.style.minHeight = '';
  };

  if (reduceMotion() || !pendingState) {
    setType(OPEN_TYPE);
    gsap.set(factsRef.value, { autoAlpha: 1, y: 0 });
    gsap.set(closeRef.value, { autoAlpha: 1, scale: 1 });
    timeline = gsap.timeline({ onComplete: showAll });
    timeline.to(scrim, { opacity: 1, duration: 0.15, ease: 'none' }, 0);
    focusClose();
    pendingState = null;
    return;
  }

  /*
   * ② Last 必须按「终态字号」量取，否则 Flip 会以缩回小卡字号后的内容高度作为落点，
   *    动画结束清除行内 height 时卡片回落到真实高度 —— 表现为最后一刻突然变高。
   *    因此 ③ Invert + Play 之后再把文字缩到小卡字号，作为 tween 起点。
   */
  timeline = Flip.from(pendingState, {
    props: FLIP_PROPS,
    scale: false,
    duration: OPEN_DURATION,
    ease: OPEN_EASE
  });
  pendingState = null;
  // Flip 已把起止值写进行内样式，放开 min-height 才能缩回小卡尺寸
  el.style.minHeight = '0px';
  setType(MINI_TYPE);

  timeline
    .to(scrim, { opacity: 1, duration: 0.18, ease: 'none' }, 0)
    // 课程名 / 地点 / 教师就是同一批节点，字号随容器同步放大（共享元素的文字部分）
    .to(
      titleRef.value,
      { fontSize: OPEN_TYPE.title[0], lineHeight: OPEN_TYPE.title[1], duration: OPEN_DURATION, ease: OPEN_EASE },
      0
    )
    .to(
      locationRef.value,
      { fontSize: OPEN_TYPE.sub[0], lineHeight: OPEN_TYPE.sub[1], duration: OPEN_DURATION, ease: OPEN_EASE },
      0
    )
    .to(
      teacherRef.value,
      { fontSize: OPEN_TYPE.teacher[0], lineHeight: OPEN_TYPE.teacher[1], duration: OPEN_DURATION, ease: OPEN_EASE },
      0
    )
    .to(factsRef.value, { autoAlpha: 1, y: 0, duration: 0.26, ease: 'power2.out' }, OPEN_DURATION * 0.3)
    .to(closeRef.value, { autoAlpha: 1, scale: 1, duration: 0.24, ease: 'back.out(1.7)' }, OPEN_DURATION * 0.34)
    .eventCallback('onComplete', showAll);

  focusClose();
}

function focusClose() {
  requestAnimationFrame(() => closeRef.value?.focus?.({ preventScroll: true }));
}

async function requestClose() {
  // 只有已展开的大卡才需要退场流程（Esc 落在小卡上时不应触发）
  if (!props.expanded || closing.value) return;
  closing.value = true;
  const el = cardRef.value;
  if (!el) {
    finish();
    return;
  }
  timeline?.kill();

  // ① First：大卡当前状态
  const openState = Flip.getState(el, { props: FLIP_PROPS });

  if (reduceMotion()) {
    emit('close', props.course);
    finish();
    return;
  }

  // ② 立即切回小卡内容（节点暂时仍留在 body 内，避免被课表网格的 overflow 裁切）
  emit('close', props.course);
  await nextTick();
  if (!cardRef.value) {
    finish();
    return;
  }

  const target = cardRef.value;
  const home = homeRect;
  target.style.minHeight = '0px';
  if (home) {
    target.style.width = `${home.width}px`;
    target.style.height = `${home.height}px`;
    target.style.left = `${home.left}px`;
    target.style.top = `${home.top}px`;
  }

  // ③ 反向 FLIP：同一个元素从大卡几何飞回它在网格里的位置，附属内容与遮罩同步淡出
  timeline = Flip.from(openState, {
    props: FLIP_PROPS,
    scale: false,
    duration: CLOSE_DURATION,
    ease: CLOSE_EASE,
    onComplete: finish
  });

  timeline
    .to([factsRef.value, closeRef.value], { autoAlpha: 0, duration: 0.12, ease: 'power1.out' }, 0)
    .to(scrimRef.value, { opacity: 0, duration: 0.2, ease: 'power1.in' }, 0.06)
    .to(
      titleRef.value,
      { fontSize: MINI_TYPE.title[0], lineHeight: MINI_TYPE.title[1], duration: CLOSE_DURATION, ease: CLOSE_EASE },
      0
    )
    .to(
      locationRef.value,
      { fontSize: MINI_TYPE.sub[0], lineHeight: MINI_TYPE.sub[1], duration: CLOSE_DURATION, ease: CLOSE_EASE },
      0
    )
    .to(
      teacherRef.value,
      { fontSize: MINI_TYPE.teacher[0], lineHeight: MINI_TYPE.teacher[1], duration: CLOSE_DURATION, ease: CLOSE_EASE },
      0
    );
}

/** 收尾：清掉过渡期间的临时行内几何，节点回到网格内的正常流 */
function finish() {
  const el = cardRef.value;
  if (el) {
    el.style.left = '';
    el.style.top = '';
    el.style.width = '';
    el.style.height = '';
    el.style.minHeight = '';
    gsap.set(el, { clearProps: 'transform,opacity,visibility' });
    // 字号也已回落到小卡的 CSS 值，清掉行内样式让后续状态切换完全交给 CSS
    gsap.set([titleRef.value, locationRef.value, teacherRef.value], {
      clearProps: 'fontSize,lineHeight'
    });
  }
  closing.value = false;
  pendingState = null;
  homeRect = null;
  const focused = lastFocused;
  lastFocused = null;
  if (focused && typeof focused.focus === 'function') {
    requestAnimationFrame(() => focused.focus({ preventScroll: true }));
  }
}

onBeforeUnmount(() => {
  timeline?.kill();
  if (cardRef.value) gsap.killTweensOf(cardRef.value);
  if (scrimRef.value) gsap.killTweensOf(scrimRef.value);
});
</script>

<style scoped>
/* ---------------- 小卡（默认态） ---------------- */
.tt-card {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  box-sizing: border-box;
  width: 100%;
  height: 100%;
  padding: 10px;
  border-width: 1px;
  border-style: solid;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.25;
  text-align: left;
  cursor: pointer;
  user-select: none;
  overflow: hidden;
}

.tt-title {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 14px;
  line-height: 18px;
  font-weight: 700;
  letter-spacing: -0.01em;
  overflow-wrap: anywhere;
}

.tt-location {
  margin-top: 6px;
  font-size: 12px;
  line-height: 16px;
  opacity: 0.8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tt-teacher {
  margin-top: 6px;
  font-size: 11px;
  line-height: 14px;
  opacity: 0.75;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tt-head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

/*
 * 卡片高度在 FLIP 期间从大卡尺寸动画回小卡尺寸，内容会短暂高于盒子。
 * 若允许这些行参与 flex 收缩，带 overflow:hidden（省略号）的文本行会被压扁成一条，
 * 观感就是「字体高度被压缩」——因此一律不参与收缩，超出部分交给卡片的 overflow 裁切。
 */
.tt-top,
.tt-teacher,
.tt-facts {
  flex: none;
}

/* ---------------- 展开态：同一个节点，脱离网格后铺到屏幕中央 ---------------- */
.tt-card.is-floating {
  position: fixed;
  z-index: 71;
  width: min(100vw - 48px, 520px);
  height: auto;
  /* 高度贴合内容（明细行数少的课程不会再被撑出大片空白），只留一个很低的兜底 */
  min-height: 200px;
  max-height: calc(100vh - 64px);
  cursor: default;
  overflow: hidden;
  opacity: 1;
}

/* 大卡视觉：内边距 / 圆角 / 字号（FLIP 会以这些终值参与插值） */
.tt-card.is-open {
  padding: 20px 22px;
  border-radius: 22px;
  /* 课程名 / 地点 / 教师成组靠上，明细紧随其后（不再用 auto 撑开间距） */
  justify-content: flex-start;
}

.tt-card.is-open .tt-facts {
  margin-top: 12px;
}

.tt-card.is-open .tt-title {
  font-size: 22px;
  line-height: 28px;
}

.tt-card.is-open .tt-location,
.tt-card.is-open .tt-teacher {
  margin-top: 6px;
  font-size: 15px;
  line-height: 20px;
  white-space: normal;
  overflow: visible;
  opacity: 0.85;
}

.tt-close {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 9999px;
  border: none;
  padding: 0;
  cursor: pointer;
  color: inherit;
  /* Android：content 色 10% 透明底；color-mix 不支持时退回浅灰 */
  background-color: rgba(0, 0, 0, 0.06);
  background-color: color-mix(in srgb, currentColor 10%, transparent);
}

.tt-close:hover {
  background-color: rgba(0, 0, 0, 0.1);
  background-color: color-mix(in srgb, currentColor 20%, transparent);
}

.tt-facts {
  margin-top: 4px;
}

.tt-divider {
  height: 0;
  border-top: 1px solid currentColor;
  opacity: 0.16;
  margin-bottom: 2px;
}

.tt-row {
  display: flex;
  align-items: flex-start;
  padding: 8px 0;
}

.tt-row-label {
  flex: none;
  width: 80px;
  font-size: 13px;
  opacity: 0.55;
}

.tt-row-value {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 14px;
  font-weight: 500;
  overflow-wrap: anywhere;
}

/* ---------------- 遮罩 ---------------- */
.tt-scrim {
  position: fixed;
  inset: 0;
  z-index: 70;
  /* 直接模糊遮罩背后的课表（等价 Android 的 12dp blur，且不会让卡片变成被包含的固定定位元素） */
  -webkit-backdrop-filter: blur(12px);
  backdrop-filter: blur(12px);
}

@media (prefers-reduced-motion: reduce) {
  .tt-scrim {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }
}
</style>
