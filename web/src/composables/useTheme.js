import { ref, computed } from 'vue';
import { storageGetItem, storageSetItem } from '@/services/storage.js';

const THEME_KEY = 'better_hrbust_theme';

const currentTheme = ref(storageGetItem(THEME_KEY) || 'system');
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

/** 真正把主题类名写到 <html> 上（View Transition 的更新回调里调用） */
function commitTheme(theme) {
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
    currentTheme.value = theme;
    storageSetItem(THEME_KEY, theme);
    applyTheme(theme, { animate: true });
  }

  function toggleTheme() {
    setTheme(isDark.value ? 'light' : 'dark');
  }

  return {
    isDark,
    themeMode: currentTheme,
    setTheme,
    toggleTheme
  };
}
