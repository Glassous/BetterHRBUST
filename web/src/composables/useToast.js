import { ref } from 'vue';

const toasts = ref([]);
const announcement = ref('');
const timers = new Map();
let idCounter = 0;

function scheduleDismiss(id, duration) {
  if (!duration || duration <= 0) return;
  clearTimer(id);
  const timer = setTimeout(() => removeToast(id), duration);
  timers.set(id, { timer, duration });
}

function clearTimer(id) {
  const entry = timers.get(id);
  if (entry) {
    clearTimeout(entry.timer);
  }
}

function removeToast(id) {
  clearTimer(id);
  timers.delete(id);
  const idx = toasts.value.findIndex(t => t.id === id);
  if (idx !== -1) {
    toasts.value.splice(idx, 1);
  }
}

function pauseToast(id) {
  clearTimer(id);
}

function resumeToast(id) {
  const entry = timers.get(id);
  if (!entry) return;
  scheduleDismiss(id, entry.duration);
}

export function useToast() {
  function showToast({ title, message = '', type = 'info', duration = 3200 }) {
    const id = ++idCounter;
    toasts.value.push({ id, title, message, type, duration });
    const text = message ? `${title}：${message}` : title;
    announcement.value = '';
    if (typeof document !== 'undefined' && typeof document.ariaNotify === 'function') {
      document.ariaNotify(text);
    } else {
      queueMicrotask(() => {
        announcement.value = text;
      });
    }
    scheduleDismiss(id, duration);
  }

  return {
    toasts,
    announcement,
    showToast,
    removeToast,
    pauseToast,
    resumeToast
  };
}
