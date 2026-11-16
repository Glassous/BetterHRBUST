/**
 * v-overlay-scroll：为任意可滚动容器加一条「向内、细、自动隐藏」的覆盖式滚动条。
 *
 * 为什么不用原生滚动条：
 * - Windows 上的原生滚动条会占据布局宽度，显示/隐藏时内容会横向跳动；
 * - `::-webkit-scrollbar` 无法真正做到覆盖在内容之上。
 *
 * 这里在滚动容器的父元素里插入一条绝对定位的轨道 + 滑块：
 * - 绝对定位，不参与布局，因此不会改变容器宽度；
 * - 默认完全透明，鼠标进入容器时淡淡显示，按住/悬停滑块时完全显示；
 * - 滚动后短暂显示，停手约 900ms 自动淡出；
 * - 原生滚动条通过 `.overlay-scroll` 隐藏（仍可用滚轮、触控板和键盘滚动）。
 */

const THUMB_MIN = 32;
const HIDE_DELAY = 900;
const registry = new WeakMap();

function attach(el) {
  if (!el || registry.has(el)) return;
  const parent = el.parentElement;
  if (!parent) return;

  if (getComputedStyle(parent).position === 'static') {
    parent.style.position = 'relative';
  }

  el.classList.add('overlay-scroll');

  const track = document.createElement('div');
  track.className = 'ov-scroll-track';
  track.setAttribute('aria-hidden', 'true');
  const thumb = document.createElement('div');
  thumb.className = 'ov-scroll-thumb';
  track.appendChild(thumb);
  parent.appendChild(track);

  let hideTimer = 0;
  let dragging = false;
  let dragStartY = 0;
  let dragStartTop = 0;
  let hovering = false;

  function isScrollable() {
    return el.scrollHeight > el.clientHeight + 1;
  }

  function layout() {
    const parentRect = parent.getBoundingClientRect();
    const rect = el.getBoundingClientRect();
    const inset = 4;
    track.style.top = `${Math.round(rect.top - parentRect.top + inset)}px`;
    track.style.height = `${Math.max(0, Math.round(rect.height - inset * 2))}px`;
    track.style.right = `${Math.max(0, Math.round(parentRect.right - rect.right + 2))}px`;
    updateThumb();
  }

  function updateThumb() {
    const scrollable = isScrollable();
    track.style.display = scrollable ? 'block' : 'none';
    if (!scrollable) return;

    const trackHeight = Math.max(0, el.clientHeight - 8);
    const ratio = Math.min(1, el.clientHeight / el.scrollHeight);
    const height = Math.max(THUMB_MIN, Math.round(trackHeight * ratio));
    const maxTop = Math.max(0, trackHeight - height);
    const maxScroll = Math.max(1, el.scrollHeight - el.clientHeight);
    const top = Math.round(maxTop * (el.scrollTop / maxScroll));

    thumb.style.height = `${height}px`;
    thumb.style.top = `${top}px`;
  }

  function show(faint) {
    if (!isScrollable()) return;
    track.classList.add('is-visible');
    track.classList.toggle('is-faint', faint);
  }

  function hideSoon() {
    if (dragging || hovering) return;
    window.clearTimeout(hideTimer);
    hideTimer = window.setTimeout(() => {
      if (!dragging && !hovering) {
        track.classList.remove('is-visible', 'is-faint');
      }
    }, HIDE_DELAY);
  }

  function onScroll() {
    updateThumb();
    track.classList.remove('is-faint');
    show(false);
    hideSoon();
  }

  function onEnter() {
    hovering = true;
    show(true);
  }

  function onLeave() {
    hovering = false;
    if (!dragging) {
      window.clearTimeout(hideTimer);
      track.classList.remove('is-visible', 'is-faint');
    }
  }

  function onThumbDown(event) {
    if (event.button !== 0) return;
    event.preventDefault();
    dragging = true;
    dragStartY = event.clientY;
    dragStartTop = thumb.offsetTop;
    track.classList.remove('is-faint');
    track.classList.add('is-visible', 'is-dragging');
    thumb.setPointerCapture?.(event.pointerId);
    window.addEventListener('pointermove', onThumbMove);
    window.addEventListener('pointerup', onThumbUp, { once: true });
  }

  function onThumbMove(event) {
    if (!dragging) return;
    const trackHeight = Math.max(0, el.clientHeight - 8);
    const thumbHeight = thumb.offsetHeight;
    const maxTop = Math.max(1, trackHeight - thumbHeight);
    const maxScroll = Math.max(1, el.scrollHeight - el.clientHeight);
    const nextTop = Math.min(maxTop, Math.max(0, dragStartTop + (event.clientY - dragStartY)));
    el.scrollTop = (nextTop / maxTop) * maxScroll;
  }

  function onThumbUp() {
    dragging = false;
    track.classList.remove('is-dragging');
    window.removeEventListener('pointermove', onThumbMove);
    hideSoon();
  }

  const resizeObserver = typeof ResizeObserver === 'function'
    ? new ResizeObserver(() => layout())
    : null;
  const mutationObserver = typeof MutationObserver === 'function'
    ? new MutationObserver(() => {
        updateThumb();
        layout();
      })
    : null;

  el.addEventListener('scroll', onScroll, { passive: true });
  el.addEventListener('pointerenter', onEnter);
  el.addEventListener('pointerleave', onLeave);
  thumb.addEventListener('pointerdown', onThumbDown);
  window.addEventListener('resize', layout);
  resizeObserver?.observe(el);
  resizeObserver?.observe(parent);
  mutationObserver?.observe(el, { childList: true, subtree: true });

  registry.set(el, {
    cleanup() {
      window.clearTimeout(hideTimer);
      window.removeEventListener('pointermove', onThumbMove);
      window.removeEventListener('resize', layout);
      el.removeEventListener('scroll', onScroll);
      el.removeEventListener('pointerenter', onEnter);
      el.removeEventListener('pointerleave', onLeave);
      thumb.removeEventListener('pointerdown', onThumbDown);
      resizeObserver?.disconnect();
      mutationObserver?.disconnect();
      el.classList.remove('overlay-scroll');
      track.remove();
    },
    refresh: layout
  });

  requestAnimationFrame(layout);
}

export const overlayScroll = {
  mounted(el) {
    attach(el);
  },
  updated(el) {
    registry.get(el)?.refresh();
  },
  unmounted(el) {
    registry.get(el)?.cleanup();
    registry.delete(el);
  }
};

export default overlayScroll;
