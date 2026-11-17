import { ref, computed } from 'vue';
import { storageGetItem, storageRemoveItem, storageSetItem } from '@/services/storage.js';

const THEME_KEY = 'better_hrbust_theme';

function normalizeTheme(value) {
  return value === 'light' || value === 'dark' ? value : 'system';
}

const currentTheme = ref(normalizeTheme(storageGetItem(THEME_KEY)));
const mediaQuery = typeof window !== 'undefined' && window.matchMedia
  ? window.matchMedia('(prefers-color-scheme: dark)') : null;
const systemDark = ref(mediaQuery?.matches || false);

function prefersReducedMotion() {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** 解析某个主题模式最终是否为深色 */
function resolveDark(theme) {
  return theme === 'dark' || (theme === 'system' && systemDark.value);
}

function syncColorSchemeMeta(theme) {
  if (typeof document === 'undefined' || !document.head) return;
  let meta = document.head.querySelector('meta[name="color-scheme"]');
  if (!meta) {
    meta = document.createElement('meta');
    meta.name = 'color-scheme';
    document.head.appendChild(meta);
  }
  meta.content = theme === 'light' || theme === 'dark' ? theme : 'light dark';
}

/** 真正把主题类名和 color-scheme 元信息写到文档上（View Transition 的更新回调里调用） */
function commitTheme(theme) {
  if (typeof document === 'undefined') return;
  syncColorSchemeMeta(theme);
  document.documentElement.classList.toggle('dark', resolveDark(theme));
}

/**
 * 应用主题。
 * animate=true 时用 View Transitions 做「右上角 → 左下角」的圆形扩散：
 * 新主题快照从右上角为圆心逐渐铺满整个屏幕，旧主题作为底层保持不动。
 * 浏览器不支持 View Transition 或用户偏好减少动态效果时，直接切换。
 */
function applyTheme(theme, { animate = false } = {}) {
  const root = document.documentElement;
  const nextDark = resolveDark(theme);
  const changed = root.classList.contains('dark') !== nextDark;

  if (!animate || !changed || typeof document.startViewTransition !== 'function' || prefersReducedMotion()) {
    commitTheme(theme);
    return;
  }

  // 关闭元素级颜色过渡，保证新快照截到的是最终配色，而不是过渡中间色
  root.classList.add('theme-switching');
  const transition = document.startViewTransition(() => commitTheme(theme));
  const cleanup = () => root.classList.remove('theme-switching');
  transition.finished.then(cleanup).catch(cleanup);

  transition.ready
    .then(() => {
      // 圆心固定在视口右上角，半径覆盖到最远的左下角
      const x = window.innerWidth;
      const y = 0;
      const radius = Math.hypot(window.innerWidth, window.innerHeight);
      root.animate(
        {
          clipPath: [
            `circle(0px at ${x}px ${y}px)`,
            `circle(${radius}px at ${x}px ${y}px)`
          ]
        },
        {
          duration: 520,
          easing: 'cubic-bezier(0.2, 0, 0, 1)',
          pseudoElement: '::view-transition-new(root)'
        }
      );
    })
    .catch(() => {});
}

// Listen to OS theme changes when in system mode
if (mediaQuery) {
  mediaQuery.addEventListener('change', event => {
    systemDark.value = event.matches;
    if (currentTheme.value === 'system') {
      applyTheme('system', { animate: true });
    }
  });
}

// 跨标签页同步主题覆盖
if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
  window.addEventListener('storage', event => {
    if (event.key !== THEME_KEY) return;
    const next = normalizeTheme(event.newValue);
    if (next === currentTheme.value) return;
    currentTheme.value = next;
    applyTheme(next);
  });
}

// Initial apply
applyTheme(currentTheme.value);

export function useTheme() {
  const isDark = computed(() => {
    if (currentTheme.value === 'system') {
      return systemDark.value;
    }
    return currentTheme.value === 'dark';
  });

  function setTheme(theme) {
    const normalized = normalizeTheme(theme);
    currentTheme.value = normalized;
    if (normalized === 'system') {
      storageRemoveItem(THEME_KEY);
    } else {
      storageSetItem(THEME_KEY, normalized);
    }
    applyTheme(normalized, { animate: true });
  }

  function toggleTheme() {
    const target = isDark.value ? 'light' : 'dark';
    // 若目标色系恰好与当前系统偏好一致，则恢复为跟随系统（避免无意中永久锁定）
    setTheme(target === (systemDark.value ? 'dark' : 'light') ? 'system' : target);
  }

  return {
    isDark,
    themeMode: currentTheme,
    setTheme,
    toggleTheme
  };
}
