<template>
  <!-- 宽屏（xl+）：推挤式面板，作为布局的右侧列存在，打开时压缩主区域而非覆盖其上；
       中窄屏（md–xl）：带遮罩的覆盖式侧栏；
       手机（<md）：底部抽屉，顶部把手可拖动调整高度 / 下拉关闭。
       宽度与高度动画统一放在外层，内层铺满外层，过渡期间内容不会重排抖动。 -->
  <div
    v-if="open"
    class="fixed inset-0 z-30 bg-[var(--bg-backdrop)] backdrop-blur-[1px] xl:hidden"
    @click="close"
  />
  <div
    class="fixed inset-x-0 bottom-0 z-40 min-h-0 overflow-hidden rounded-t-[var(--shape-xxl)] shadow-[var(--shadow-float)] transition-[width,height] duration-300 ease-out motion-reduce:transition-none md:max-xl:left-auto md:max-xl:right-0 md:max-xl:top-[var(--tb)] md:max-xl:bottom-0 md:max-xl:rounded-t-none md:max-xl:rounded-l-[var(--shape-xxl)] xl:static xl:inset-auto xl:h-full xl:shrink-0 xl:rounded-none xl:shadow-none"
    :class="[
      open ? 'w-full md:max-xl:w-[400px] xl:w-[440px]' : 'w-full md:max-xl:w-0 xl:w-0',
      open ? '' : 'max-md:shadow-none'
    ]"
    :style="sheetStyle"
    :inert="open ? null : ''"
    :aria-hidden="open ? 'false' : 'true'"
  >
    <aside
      class="w-full h-full min-h-0 flex flex-col bg-[var(--md-surface-container-low)] transition-opacity duration-200 ease-out motion-reduce:transition-none"
      :class="open ? 'opacity-100' : 'opacity-0'"
      role="complementary"
      aria-label="AI 对话"
    >
          <!-- 手机端拖动把手 -->
          <div
            class="md:hidden flex h-6 shrink-0 items-center justify-center touch-none select-none"
            :class="draggingSheet ? 'cursor-grabbing' : 'cursor-grab'"
            aria-hidden="true"
            @pointerdown.prevent="startSheetDrag"
            @pointermove.prevent="moveSheetDrag"
            @pointerup="endSheetDrag"
            @pointercancel="endSheetDrag"
          >
            <span class="h-1 w-9 rounded-full bg-[var(--md-on-surface-variant)]/35" />
          </div>

          <!-- 头部 -->
          <div class="px-5 pt-2 md:pt-5 pb-3 flex items-center justify-between gap-3">
            <div class="min-w-0">
              <h3 class="text-[17px] font-semibold text-[var(--ink-primary)] tracking-tight truncate">AI 对话</h3>
              <p class="text-[12px] text-[var(--ink-muted)] mt-0.5 truncate">
                <template v-if="streaming">生成中<template v-if="round"> · 第 {{ round }} 轮</template></template>
                <template v-else-if="ready">就绪 · {{ provider }}</template>
                <template v-else>尚未配置</template>
              </p>
            </div>
            <div class="flex items-center gap-1 shrink-0">
              <button
                type="button"
                class="px-3 h-9 rounded-full text-[12px] font-medium text-[var(--ink-secondary)] hover:text-[var(--ink-primary)] hover:bg-[var(--md-surface-container-high)] cursor-pointer transition-colors"
                @click="openSettings"
              >
                设置
              </button>
              <button
                type="button"
                aria-label="关闭对话"
                class="md-icon-btn w-9 h-9 cursor-pointer"
                @click="close"
              >
                <Icon name="x" customClass="w-4 h-4" />
              </button>
            </div>
          </div>

          <!-- 消息区 -->
          <div ref="scrollRef" v-overlay-scroll class="flex-1 min-h-0 overflow-y-auto px-5 py-4 space-y-3.5">
            <!-- 未就绪引导 -->
            <div
              v-if="!ready"
              class="rounded-[var(--shape-lg)] bg-[var(--md-surface-container)] p-4 space-y-3"
            >
              <p class="text-[13px] font-semibold text-[var(--ink-primary)]">三步开始使用</p>
              <ol class="text-[13px] text-[var(--ink-secondary)] space-y-1.5 list-decimal pl-4 leading-relaxed">
                <li>在设置页填写接口地址与模型名（OpenAI 兼容协议）</li>
                <li>填写 API Key 并保存</li>
                <li>打开「AI 对话」并同意数据出网</li>
              </ol>
              <p class="text-[12px] text-[var(--ink-muted)] leading-relaxed">当前状态：{{ readiness.reason }}</p>
              <div class="flex flex-wrap gap-2 text-[12px] text-[var(--ink-muted)]">
                <span class="px-2 py-1 rounded-full bg-[var(--md-surface-container-high)]">DeepSeek · https://api.deepseek.com/v1</span>
                <span class="px-2 py-1 rounded-full bg-[var(--md-surface-container-high)]">Ollama · http://127.0.0.1:11434/v1</span>
              </div>
              <UiButton variant="outline" size="sm" @click="openSettings">前往设置</UiButton>
            </div>

            <!-- 空态示例 -->
            <div v-else-if="entries.length === 0" class="space-y-2.5">
              <p class="text-[13px] text-[var(--ink-muted)]">
                用自然语言提问，模型通过只读工具查询本地缓存，数字与页面完全一致。
              </p>
              <button
                v-for="sample in samples"
                :key="sample"
                type="button"
                class="w-full text-left px-4 py-2.5 rounded-[var(--shape-lg)] bg-[var(--md-surface-container)] hover:bg-[var(--md-surface-container-high)] text-[13px] text-[var(--ink-secondary)] cursor-pointer transition-colors"
                @click="fill(sample)"
              >
                {{ sample }}
              </button>
            </div>

            <!-- 消息列表 -->
            <template v-for="entry in entries" :key="entry.id">
              <div v-if="entry.role === 'user'" class="flex justify-end">
                <div
                  class="max-w-[85%] rounded-[var(--shape-xl)] rounded-br-[var(--shape-xs)] px-4 py-2.5 bg-[var(--md-primary-container)] text-[13.5px] leading-relaxed text-[var(--md-on-primary-container)] whitespace-pre-wrap break-words"
                >
                  {{ entry.text }}
                </div>
              </div>

              <div v-else-if="entry.role === 'assistant'" class="text-[13.5px] leading-relaxed text-[var(--ink-primary)]">
                <div v-if="entry.pending" class="whitespace-pre-wrap break-words">
                  {{ entry.text }}<span class="inline-block w-1.5 h-3.5 ml-0.5 align-middle bg-[var(--accent)] animate-pulse" />
                </div>
                <div v-else class="md-body" v-html="render(entry.text)" />
              </div>

              <div v-else-if="entry.role === 'tool'" class="flex items-center gap-2 text-[12px] text-[var(--ink-muted)]">
                <span
                  class="w-1.5 h-1.5 rounded-full shrink-0"
                  :class="entry.pending ? 'bg-[var(--accent)] animate-pulse' : 'bg-[var(--sem-success)]'"
                />
                <span class="truncate">{{ entry.text }}</span>
              </div>

              <div
                v-else-if="entry.role === 'error'"
                class="rounded-[var(--shape-md)] bg-[var(--sem-danger-soft)] px-4 py-2.5 text-[13px] text-[var(--ink-primary)] whitespace-pre-wrap break-words"
              >
                <span class="font-semibold text-[var(--sem-danger)]">出错了</span>
                <span class="ml-2">{{ entry.text }}</span>
              </div>

              <div v-else class="text-[12px] text-[var(--ink-muted)] italic">{{ entry.text }}</div>
            </template>
          </div>

          <!-- 输入区 -->
          <form
            class="p-3.5 space-y-2"
            :style="isMobileSheet ? { paddingBottom: 'calc(14px + env(safe-area-inset-bottom, 0px))' } : undefined"
            @submit.prevent="submit"
          >
            <label :for="chatInputId" class="visually-hidden">向 AI 助手提问</label>
            <textarea
              :id="chatInputId"
              v-model="input"
              rows="2"
              :disabled="streaming"
              enterkeyhint="send"
              placeholder="问点什么，例如“我这学期绩点多少”“明天有什么课”（Enter 发送，Shift+Enter 换行）"
              class="w-full resize-none rounded-[var(--shape-lg)] bg-[var(--md-surface-container)] px-4 py-3 text-[13.5px] text-[var(--ink-primary)] placeholder:text-[var(--ink-muted)] outline-none border border-transparent focus:bg-[var(--bg-surface)] focus:border-[var(--md-primary)] transition-colors disabled:opacity-60"
              @keydown="onKeydown"
            />
            <div class="flex items-center justify-between gap-2">
              <div class="flex items-center gap-1.5">
                <UiButton v-if="!streaming" variant="ghost" size="sm" @click="reset">清空</UiButton>
                <UiButton v-else variant="ghost" size="sm" @click="cancel">停止</UiButton>
                <span class="text-[11.5px] text-[var(--ink-muted)]">对话不保存到本地</span>
              </div>
              <UiButton type="submit" variant="primary" size="sm" :disabled="!canChat || !input.trim()">发送</UiButton>
            </div>
          </form>
    </aside>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue';
import Icon from '@/components/icons/Icon.vue';
import UiButton from '@/components/ui/UiButton.vue';
import { renderMarkdown } from '@/utils/markdown.js';
import { useAiChat } from '@/composables/useAiChat.js';
import { useSession } from '@/composables/useSession.js';

const { open, entries, streaming, round, input, readiness, ready, provider, canChat, toggle, send, cancel, reset } =
  useAiChat();
const { navigateTo } = useSession();

const chatInputId = useId();
const scrollRef = ref(null);

/* ---------------- 手机底部抽屉 ---------------- */
const mobileSheetMedia = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
  ? window.matchMedia('(max-width: 767px)')
  : null;
const isMobileSheet = ref(mobileSheetMedia?.matches || false);
const sheetHeight = ref(0);
const draggingSheet = ref(false);
let sheetStartY = 0;
let sheetStartHeight = 0;
let sheetLastY = 0;
let sheetLastTime = 0;

/** 默认高度：约 72vh，限制在 360px – 92vh 之间 */
function defaultSheetHeight() {
  const vh = window.innerHeight;
  return Math.round(Math.min(Math.max(vh * 0.72, 360), vh * 0.92));
}

function maxSheetHeight() {
  return Math.round(window.innerHeight * 0.92);
}

const sheetStyle = computed(() => {
  if (!isMobileSheet.value) return undefined;
  return {
    height: `${sheetHeight.value}px`,
    // 拖动过程中关闭高度过渡，让抽屉严格跟手
    transition: draggingSheet.value ? 'none' : undefined
  };
});

function startSheetDrag(event) {
  if (!isMobileSheet.value || !open.value) return;
  if (event.button !== undefined && event.button !== 0) return;
  draggingSheet.value = true;
  sheetStartY = event.clientY;
  sheetStartHeight = sheetHeight.value || defaultSheetHeight();
  sheetLastY = event.clientY;
  sheetLastTime = performance.now();
  event.currentTarget.setPointerCapture?.(event.pointerId);
}

function moveSheetDrag(event) {
  if (!draggingSheet.value) return;
  const dy = event.clientY - sheetStartY;
  const next = Math.max(0, Math.min(maxSheetHeight(), sheetStartHeight - dy));
  sheetHeight.value = Math.round(next);
  sheetLastY = event.clientY;
  sheetLastTime = performance.now();
}

function endSheetDrag(event) {
  if (!draggingSheet.value) return;
  draggingSheet.value = false;

  const vh = window.innerHeight;
  const dy = event.clientY - sheetStartY;
  const elapsed = Math.max(1, performance.now() - sheetLastTime);
  const velocity = (event.clientY - sheetLastY) / elapsed; // 正值 = 向下
  const height = sheetHeight.value || sheetStartHeight;

  // 快速下滑 / 下拉超过 140px / 高度低于 42%：关闭
  if (velocity > 0.8 || dy > 140 || height < vh * 0.42) {
    close();
    return;
  }

  // 拖到 82% 以上吸顶展开，否则回到默认高度
  sheetHeight.value = height > vh * 0.82 ? maxSheetHeight() : defaultSheetHeight();
}

function onMobileSheetMediaChange(event) {
  isMobileSheet.value = event.matches;
  if (!event.matches) {
    sheetHeight.value = 0;
    return;
  }
  if (open.value) sheetHeight.value = defaultSheetHeight();
}

const samples = [
  '我这学期的绩点是多少？',
  '今天还有什么课？',
  '最近有哪些考试？',
  '有哪些必修课挂科了？'
];

function render(text) {
  return renderMarkdown(text || '');
}

function fill(sample) {
  input.value = sample;
}

function close() {
  toggle(false);
}

function openSettings() {
  toggle(false);
  navigateTo('settings');
}

function submit() {
  if (streaming.value) return;
  send();
}

function onKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    // 兼容输入法合成期间按 Enter 确认选词（含 Safari keyCode === 229 事件顺序差异）
    if (event.isComposing || event.keyCode === 229) return;
    submit();
  }
}

function onEscape(event) {
  if (event.key === 'Escape' && open.value) close();
}

/** ctrl+j 由 AppLayout 统一处理，这里只管 esc */
onMounted(() => {
  document.addEventListener('keydown', onEscape);
  mobileSheetMedia?.addEventListener('change', onMobileSheetMediaChange);
  if (isMobileSheet.value && open.value) sheetHeight.value = defaultSheetHeight();
});

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onEscape);
  mobileSheetMedia?.removeEventListener('change', onMobileSheetMediaChange);
});

watch(
  () => [entries.value.length, entries.value[entries.value.length - 1]?.text, streaming.value],
  async () => {
    await nextTick();
    const el = scrollRef.value;
    if (el) el.scrollTop = el.scrollHeight;
  }
);

// 抽屉打开时把焦点交给输入框，符合"打开即能问"的预期；手机端不自动聚焦，避免键盘立刻顶起抽屉
watch(open, async value => {
  if (!value || isMobileSheet.value) return;
  await nextTick();
  const area = document.querySelector('[aria-label="AI 对话"] textarea');
  if (area && typeof area.focus === 'function') area.focus();
});

// 手机端：打开时从底部升起，关闭时落回底部
watch(open, value => {
  if (!isMobileSheet.value) return;
  if (!value) {
    sheetHeight.value = 0;
    return;
  }
  sheetHeight.value = 0;
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      if (open.value && isMobileSheet.value) sheetHeight.value = defaultSheetHeight();
    });
  });
});
</script>

<style scoped>
/* v-html 内容不受 scoped 约束，用 :deep 命中 */
.md-body :deep(p) {
  margin: 0.4em 0;
}
.md-body :deep(h1),
.md-body :deep(h2),
.md-body :deep(h3) {
  font-weight: 700;
  margin: 0.6em 0 0.3em;
  font-size: 1.02em;
}
.md-body :deep(ul),
.md-body :deep(ol) {
  margin: 0.35em 0;
  padding-left: 1.35em;
}
.md-body :deep(li) {
  margin: 0.15em 0;
}
.md-body :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.92em;
  background: var(--bg-subtle);
  border-radius: 4px;
  padding: 0.1em 0.32em;
}
.md-body :deep(pre) {
  background: var(--bg-subtle);
  border: 1px solid var(--border-hairline);
  border-radius: var(--radius-control);
  padding: 0.7em 0.85em;
  overflow-x: auto;
  margin: 0.5em 0;
}
.md-body :deep(pre code) {
  background: transparent;
  padding: 0;
}
.md-body :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 0.5em 0;
  font-size: 0.95em;
}
.md-body :deep(th),
.md-body :deep(td) {
  border: 1px solid var(--border-hairline);
  padding: 0.35em 0.55em;
  text-align: left;
}
.md-body :deep(th) {
  background: var(--bg-subtle);
  font-weight: 600;
}
.md-body :deep(blockquote) {
  border-left: 3px solid var(--border-strong);
  padding-left: 0.7em;
  color: var(--ink-secondary);
  margin: 0.4em 0;
}
.md-body :deep(a) {
  color: var(--accent);
  text-decoration: underline;
  text-underline-offset: 2px;
}
</style>
