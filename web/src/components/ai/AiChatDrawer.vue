<template>
  <!-- 推挤式面板：作为布局的右侧列存在，打开时压缩主区域而非覆盖其上。
       没有遮罩层，因此切换页面 / 缩放窗口都不会关闭，与主区域各自独立滚动。
       宽度动画放在外层：主区域是 flex-1，会跟着一起平滑伸缩；
       内层保持固定宽度，过渡期间内容不会重排抖动。 -->
  <div
    class="shrink-0 h-full min-h-0 overflow-hidden transition-[width] duration-300 ease-out motion-reduce:transition-none"
    :class="open ? 'w-[400px] xl:w-[440px]' : 'w-0'"
    :inert="open ? null : ''"
    :aria-hidden="open ? 'false' : 'true'"
  >
    <aside
      class="w-[400px] xl:w-[440px] h-full min-h-0 flex flex-col bg-[var(--bg-surface)] border-l border-[var(--border-strong)] transition-opacity duration-200 ease-out motion-reduce:transition-none"
      :class="open ? 'opacity-100' : 'opacity-0'"
      role="complementary"
      aria-label="AI 对话"
    >
          <!-- 头部 -->
          <div class="px-5 py-4 border-b border-[var(--border-hairline)] flex items-center justify-between gap-3">
            <div class="min-w-0">
              <h3 class="text-base font-semibold text-[var(--ink-primary)] tracking-tight truncate">AI 对话</h3>
              <p class="text-[12px] text-[var(--ink-muted)] mt-0.5 truncate">
                <template v-if="streaming">生成中<template v-if="round"> · 第 {{ round }} 轮</template></template>
                <template v-else-if="ready">就绪 · {{ provider }}</template>
                <template v-else>尚未配置</template>
              </p>
            </div>
            <div class="flex items-center gap-1 shrink-0">
              <button
                type="button"
                class="px-2.5 h-8 rounded-[var(--radius-control)] text-[12px] text-[var(--ink-muted)] hover:text-[var(--ink-primary)] hover:bg-[var(--bg-hover)] cursor-pointer transition-colors"
                @click="openSettings"
              >
                设置
              </button>
              <button
                type="button"
                aria-label="关闭对话"
                class="w-8 h-8 rounded-[var(--radius-control)] text-[var(--ink-muted)] hover:text-[var(--ink-primary)] hover:bg-[var(--bg-hover)] cursor-pointer transition-colors flex items-center justify-center"
                @click="close"
              >
                <Icon name="x" customClass="w-4 h-4" />
              </button>
            </div>
          </div>

          <!-- 消息区 -->
          <div ref="scrollRef" class="flex-1 min-h-0 overflow-y-auto px-5 py-4 space-y-3.5">
            <!-- 未就绪引导 -->
            <div
              v-if="!ready"
              class="rounded-[var(--radius-panel)] border border-[var(--border-hairline)] bg-[var(--bg-subtle)] p-4 space-y-3"
            >
              <p class="text-[13px] font-semibold text-[var(--ink-primary)]">三步开始使用</p>
              <ol class="text-[13px] text-[var(--ink-secondary)] space-y-1.5 list-decimal pl-4 leading-relaxed">
                <li>在设置页填写接口地址与模型名（OpenAI 兼容协议）</li>
                <li>填写 API Key 并保存</li>
                <li>打开「AI 对话」并同意数据出网</li>
              </ol>
              <p class="text-[12px] text-[var(--ink-muted)] leading-relaxed">当前状态：{{ readiness.reason }}</p>
              <div class="flex flex-wrap gap-2 text-[12px] text-[var(--ink-muted)]">
                <span class="px-2 py-1 rounded-[var(--radius-badge)] bg-[var(--bg-hover)]">DeepSeek · https://api.deepseek.com/v1</span>
                <span class="px-2 py-1 rounded-[var(--radius-badge)] bg-[var(--bg-hover)]">Ollama · http://127.0.0.1:11434/v1</span>
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
                class="w-full text-left px-3.5 py-2.5 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--bg-subtle)] hover:bg-[var(--bg-hover)] hover:border-[var(--border-strong)] text-[13px] text-[var(--ink-secondary)] cursor-pointer transition-colors"
                @click="fill(sample)"
              >
                {{ sample }}
              </button>
            </div>

            <!-- 消息列表 -->
            <template v-for="entry in entries" :key="entry.id">
              <div v-if="entry.role === 'user'" class="flex justify-end">
                <div
                  class="max-w-[85%] rounded-[var(--radius-panel)] px-3.5 py-2.5 bg-[var(--accent-soft)] text-[13.5px] leading-relaxed text-[var(--ink-primary)] whitespace-pre-wrap break-words"
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
                class="rounded-[var(--radius-control)] border border-[var(--sem-danger)]/40 bg-[var(--sem-danger)]/10 px-3.5 py-2.5 text-[13px] text-[var(--ink-primary)] whitespace-pre-wrap break-words"
              >
                <span class="font-semibold text-[var(--sem-danger)]">出错了</span>
                <span class="ml-2">{{ entry.text }}</span>
              </div>

              <div v-else class="text-[12px] text-[var(--ink-muted)] italic">{{ entry.text }}</div>
            </template>
          </div>

          <!-- 输入区 -->
          <div class="border-t border-[var(--border-hairline)] p-3.5 space-y-2">
            <textarea
              v-model="input"
              rows="2"
              :disabled="streaming"
              placeholder="问点什么，例如“我这学期绩点多少”“明天有什么课”（Enter 发送，Shift+Enter 换行）"
              class="w-full resize-none rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--bg-subtle)] px-3.5 py-2.5 text-[13.5px] text-[var(--ink-primary)] placeholder:text-[var(--ink-muted)] outline-none focus:border-[var(--accent)] focus:ring-2 focus:ring-[var(--accent-soft)] disabled:opacity-60"
              @keydown="onKeydown"
            />
            <div class="flex items-center justify-between gap-2">
              <div class="flex items-center gap-1.5">
                <UiButton v-if="!streaming" variant="ghost" size="sm" @click="reset">清空</UiButton>
                <UiButton v-else variant="ghost" size="sm" @click="cancel">停止</UiButton>
                <span class="text-[11.5px] text-[var(--ink-muted)]">对话不保存到本地</span>
              </div>
              <UiButton variant="primary" size="sm" :disabled="!canChat || !input.trim()" @click="submit">发送</UiButton>
            </div>
          </div>
    </aside>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import Icon from '@/components/icons/Icon.vue';
import UiButton from '@/components/ui/UiButton.vue';
import { renderMarkdown } from '@/utils/markdown.js';
import { useAiChat } from '@/composables/useAiChat.js';
import { useSession } from '@/composables/useSession.js';

const { open, entries, streaming, round, input, readiness, ready, provider, canChat, toggle, send, cancel, reset } =
  useAiChat();
const { navigateTo } = useSession();

const scrollRef = ref(null);
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
    submit();
  }
}

function onEscape(event) {
  if (event.key === 'Escape' && open.value) close();
}

/** ctrl+j 由 AppLayout 统一处理，这里只管 esc */
onMounted(() => document.addEventListener('keydown', onEscape));
onBeforeUnmount(() => document.removeEventListener('keydown', onEscape));

watch(
  () => [entries.value.length, entries.value[entries.value.length - 1]?.text, streaming.value],
  async () => {
    await nextTick();
    const el = scrollRef.value;
    if (el) el.scrollTop = el.scrollHeight;
  }
);

// 抽屉打开时把焦点交给输入框，符合"打开即能问"的预期
watch(open, async value => {
  if (!value) return;
  await nextTick();
  const area = document.querySelector('[aria-label="AI 对话"] textarea');
  if (area && typeof area.focus === 'function') area.focus();
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
