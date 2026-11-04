<template>
  <!-- 桌面端（Tauri）专属模块：显隐由 useUpdate 的 supported 决定（Web / 油猴不渲染） -->
  <UiCard v-if="supported" title="版本更新">
    <div class="space-y-4">
      <!-- 当前版本 + 手动检查入口 -->
      <div class="flex items-center justify-between gap-3">
        <div class="min-w-0">
          <div class="text-sm font-bold text-zinc-900 dark:text-zinc-100">
            当前版本
            <span class="font-mono text-zinc-500 dark:text-zinc-400">v{{ currentVersion || '未知' }}</span>
          </div>
        </div>
        <UiButton
          variant="outline"
          size="sm"
          :disabled="isChecking"
          @click="runCheck"
        >
          <template v-if="isChecking" #prefix>
            <svg
              class="animate-spin h-3.5 w-3.5 text-current"
              xmlns="http://www.w3.org/2000/svg"
              fill="none"
              viewBox="0 0 24 24"
            >
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
              <path
                class="opacity-75"
                fill="currentColor"
                d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
              />
            </svg>
          </template>
          <template v-else-if="isUpToDate" #prefix>
            <Icon name="check" custom-class="w-3.5 h-3.5" />
          </template>
          <!-- 最新状态由按钮自身表达（对号 + 已是最新），不再额外占一行状态文案 -->
          <span v-if="!isChecking">{{ isUpToDate ? '已是最新' : '检查更新' }}</span>
        </UiButton>
      </div>

      <!-- 发现新版本：这里只放入口，更新说明统一在弹窗中展示 -->
      <div
        v-if="hasUpdate"
        class="flex items-center justify-between gap-3 rounded-xl border border-amber-300/70 bg-amber-50/60 p-4 dark:border-amber-500/30 dark:bg-amber-500/10"
      >
        <div class="min-w-0">
          <div class="flex items-center gap-2">
            <Icon name="sparkles" custom-class="w-4 h-4 text-amber-600 dark:text-amber-400" />
            <span class="text-sm font-bold text-amber-800 dark:text-amber-300">
              发现新版本 v{{ latestRelease.version }}
            </span>
          </div>
          <p class="mt-1 text-[11px] text-amber-700/75 dark:text-amber-300/70">
            <template v-if="publishedText">{{ publishedText }} 发布 · </template>
            查看完整更新说明
          </p>
        </div>
        <UiButton size="sm" @click="showDialog = true">查看更新</UiButton>
      </div>

      <!-- 检测失败：仅手动检测时提示（启动静默检测失败不打扰） -->
      <p v-else-if="status === 'error'" class="text-xs text-rose-600 dark:text-rose-400">
        {{ errorMessage }}
      </p>
    </div>

    <!-- 更新说明弹窗：关闭按钮在右上角（UiModal 内置），正文渲染 Markdown -->
    <UiModal
      v-model="showDialog"
      :title="latestRelease ? `更新说明 · v${latestRelease.version}` : '更新说明'"
      max-width="lg"
    >
      <div class="space-y-4">
        <div class="flex flex-wrap items-center gap-x-2 gap-y-1 text-[11px] text-zinc-500 dark:text-zinc-400">
          <span>当前 v{{ currentVersion || '未知' }}</span>
          <span class="text-zinc-300 dark:text-zinc-600">→</span>
          <span class="font-semibold text-amber-600 dark:text-amber-400">
            最新 v{{ latestRelease?.version || '' }}
          </span>
          <template v-if="publishedText">
            <span class="text-zinc-300 dark:text-zinc-600">·</span>
            <span>{{ publishedText }} 发布</span>
          </template>
        </div>

        <div
          v-if="notesHtml"
          class="markdown-body text-[13px] leading-relaxed text-zinc-600 dark:text-zinc-300"
          v-html="notesHtml"
        ></div>
        <p v-else class="text-xs text-zinc-500 dark:text-zinc-400">
          该版本未提供更新说明，可前往 Release 页面查看详情。
        </p>
      </div>

      <template #footer>
        <UiButton variant="ghost" size="sm" @click="showDialog = false">关闭</UiButton>
        <UiButton size="sm" @click="openRelease()">
          <template #prefix>
            <Icon name="download" custom-class="w-3.5 h-3.5" />
          </template>
          前往下载
        </UiButton>
      </template>
    </UiModal>
  </UiCard>
</template>

<script setup>
import { computed, ref } from 'vue';
import UiCard from '@/components/ui/UiCard.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiModal from '@/components/ui/UiModal.vue';
import Icon from '@/components/icons/Icon.vue';
import { useUpdate } from '@/composables/useUpdate.js';
import { renderMarkdown } from '@/utils/markdown.js';

const {
  status,
  currentVersion,
  latestRelease,
  errorMessage,
  hasUpdate,
  supported,
  check,
  openRelease
} = useUpdate();

const showDialog = ref(false);

const isChecking = computed(() => status.value === 'checking');
const isUpToDate = computed(() => status.value === 'uptodate');
const notesHtml = computed(() => renderMarkdown(latestRelease.value?.notes || ''));

const publishedText = computed(() => {
  const raw = latestRelease.value?.publishedAt;
  if (!raw) return '';
  const d = new Date(raw);
  if (Number.isNaN(d.getTime())) return '';
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
});

/** 手动检测：发现新版本后直接弹出更新说明 */
async function runCheck() {
  await check();
  if (hasUpdate.value) showDialog.value = true;
}
</script>

<style scoped>
/* 更新说明排版：v-html 内容不受 scoped 属性约束，统一用 :deep 命中 */
.markdown-body :deep(h1) {
  font-size: 1.15rem;
  font-weight: 700;
  color: var(--md-heading, inherit);
  margin: 0.2rem 0 0.5rem;
}
.markdown-body :deep(h2) {
  font-size: 1.02rem;
  font-weight: 700;
  margin: 1rem 0 0.45rem;
}
.markdown-body :deep(h3) {
  font-size: 0.94rem;
  font-weight: 600;
  margin: 0.85rem 0 0.4rem;
}
.markdown-body :deep(h4),
.markdown-body :deep(h5),
.markdown-body :deep(h6) {
  font-size: 0.9rem;
  font-weight: 600;
  margin: 0.7rem 0 0.35rem;
}
.markdown-body :deep(p) {
  margin: 0.35rem 0;
}
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 0.35rem 0;
  padding-left: 1.15rem;
}
.markdown-body :deep(ul) {
  list-style: disc;
}
.markdown-body :deep(ol) {
  list-style: decimal;
}
.markdown-body :deep(li) {
  margin: 0.18rem 0;
}
.markdown-body :deep(li)::marker {
  color: rgb(161 161 170);
}
.markdown-body :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.86em;
  padding: 0.1rem 0.3rem;
  border-radius: 0.3rem;
  background: rgb(244 244 245);
  color: rgb(63 63 70);
}
.markdown-body :deep(pre) {
  margin: 0.5rem 0;
  padding: 0.75rem 0.9rem;
  border-radius: 0.6rem;
  background: rgb(24 24 27);
  color: rgb(228 228 231);
  overflow-x: auto;
}
.markdown-body :deep(pre code) {
  background: transparent;
  color: inherit;
  padding: 0;
  font-size: 0.82em;
}
.markdown-body :deep(blockquote) {
  margin: 0.5rem 0;
  padding: 0.35rem 0.75rem;
  border-left: 3px solid rgb(212 212 216);
  color: rgb(113 113 122);
}
.markdown-body :deep(hr) {
  margin: 0.9rem 0;
  border: 0;
  border-top: 1px solid rgb(228 228 231);
}
.markdown-body :deep(a) {
  color: rgb(217 119 6);
  text-decoration: underline;
  text-underline-offset: 2px;
}
.markdown-body :deep(table) {
  width: 100%;
  margin: 0.6rem 0;
  border-collapse: collapse;
  font-size: 0.86em;
}
.markdown-body :deep(th),
.markdown-body :deep(td) {
  /* 半透明中性色：浅色 / 深色主题下都能看清 */
  border: 1px solid rgba(127, 127, 127, 0.28);
  padding: 0.35rem 0.5rem;
  text-align: left;
}
.markdown-body :deep(th) {
  background: rgba(127, 127, 127, 0.16);
  color: inherit;
  font-weight: 600;
}

:global(.dark) .markdown-body :deep(code) {
  background: rgb(39 39 42);
  color: rgb(228 228 231);
}
:global(.dark) .markdown-body :deep(blockquote) {
  border-left-color: rgb(63 63 70);
  color: rgb(161 161 170);
}
:global(.dark) .markdown-body :deep(hr) {
  border-color: rgb(63 63 70);
}
</style>
