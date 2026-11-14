<template>
  <div class="space-y-6">
    <!-- 顶部搜索与分类栏 -->
    <UiCard title="资料查找" subtitle="教务处公开资料目录：分类搜索，打开学校原文或下载附件">
      <div class="flex flex-wrap items-center gap-3.5">
        <div class="flex-1 min-w-56">
          <UiInput
            v-model="query"
            aria-label="搜索资料"
            placeholder="搜索标题，例如：学生证、缓考、四六级"
            clearable
          >
            <template #prefix>
              <Icon name="search" customClass="w-4 h-4" />
            </template>
          </UiInput>
        </div>
        <UiSelect
          v-model="category"
          :items="categoryItems"
          aria-label="资料分类"
          size="md"
          plain
          custom-class="min-w-[11rem]"
        />
      </div>

      <div class="flex flex-wrap items-center justify-between gap-2 text-[13px] text-[var(--ink-muted)] mt-4 pt-3.5 border-t border-[var(--border-hairline)] num-tabular">
        <span>共 {{ filtered.length }} 条结果 · 索引更新：{{ catalog.updatedAt.slice(0, 10) }}</span>
        <a
          :href="catalog.source"
          target="_blank"
          rel="noopener noreferrer"
          class="inline-flex items-center gap-1.5 text-[var(--accent)] hover:underline font-medium"
        >
          <span>教务处资料下载原站</span>
          <Icon name="external-link" customClass="w-3.5 h-3.5" />
        </a>
      </div>
    </UiCard>

    <!-- 结构化行式目录（单卡片容器替代 20 个高卡片堆叠） -->
    <UiCard bodyClass="p-0">
      <p v-if="!filtered.length" role="status" class="px-6 py-12 text-center text-sm text-[var(--ink-muted)]">
        未找到匹配资料，请尝试其他标题关键词或分类。
      </p>

      <div v-else class="divide-y divide-[var(--border-hairline)]">
        <article
          v-for="item in visible"
          :key="item.category + item.id"
          class="px-5 sm:px-6 py-4.5 flex flex-col sm:flex-row sm:items-start justify-between gap-4 hover:bg-[var(--bg-subtle)]/45 transition-colors"
        >
          <div class="min-w-0 flex-1 space-y-2.5">
            <div class="flex flex-wrap items-center gap-2.5">
              <UiBadge size="sm" variant="default">{{ item.category }}</UiBadge>
              <span class="text-xs text-[var(--ink-muted)] num-tabular font-mono">{{ item.date || '发布日期未标注' }}</span>
            </div>

            <h4 class="text-[15px] font-semibold text-[var(--ink-primary)] leading-snug">
              {{ item.title }}
            </h4>

            <ul v-if="item.attachments.length" class="flex flex-wrap gap-2 pt-0.5">
              <li v-for="file in item.attachments" :key="file.url">
                <a
                  :href="file.url"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="inline-flex items-center gap-1.5 px-3 py-1 rounded-full border border-[var(--border-hairline)] bg-[var(--bg-subtle)] hover:bg-[var(--accent-soft)] hover:border-[var(--accent-border)] text-[13px] font-medium text-[var(--accent)] transition-colors"
                >
                  <Icon name="download" customClass="w-3.5 h-3.5 shrink-0" />
                  <span class="break-all">{{ file.title || '附件' }}</span>
                </a>
              </li>
            </ul>
            <p v-else class="text-[13px] text-[var(--ink-muted)]">
              该条目未解析到直接附件，可在原文页查看完整内容。
            </p>
          </div>

          <div class="shrink-0 pt-0.5">
            <a
              :href="item.url"
              target="_blank"
              rel="noopener noreferrer"
              class="inline-flex items-center gap-1.5 px-3.5 py-2 rounded-[var(--radius-control)] border border-[var(--border-strong)] bg-[var(--bg-surface)] hover:bg-[var(--bg-subtle)] text-[13px] font-medium text-[var(--ink-primary)] transition-all duration-150 active:scale-[0.98] whitespace-nowrap min-h-[36px]"
            >
              <span>查看学校原文</span>
              <Icon name="external-link" customClass="w-3.5 h-3.5 text-[var(--ink-muted)]" />
            </a>
          </div>
        </article>
      </div>

      <template v-if="filtered.length > 20" #footer>
        <div class="flex items-center justify-between gap-4">
          <UiButton size="sm" variant="outline" :disabled="page === 1" @click="page--">上一页</UiButton>
          <span class="text-xs sm:text-sm font-medium text-[var(--ink-secondary)] num-tabular">{{ page }} / {{ Math.ceil(filtered.length / 20) }}</span>
          <UiButton size="sm" variant="outline" :disabled="page * 20 >= filtered.length" @click="page++">下一页</UiButton>
        </div>
      </template>
    </UiCard>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue';
import UiCard from '@/components/ui/UiCard.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiInput from '@/components/ui/UiInput.vue';
import UiSelect from '@/components/ui/UiSelect.vue';
import UiBadge from '@/components/ui/UiBadge.vue';
import Icon from '@/components/icons/Icon.vue';
import catalog from '../../../shared/resources.json';

const query = ref('');
const category = ref('');
const page = ref(1);

const categoryItems = computed(() => [
  { label: '全部分类', value: '' },
  ...catalog.categories.map(item => ({ label: item.name, value: item.name }))
]);

const filtered = computed(() =>
  catalog.items.filter(
    item =>
      (!category.value || item.category === category.value) &&
      (!query.value.trim() || item.title.toLowerCase().includes(query.value.trim().toLowerCase()))
  )
);

const visible = computed(() => filtered.value.slice((page.value - 1) * 20, page.value * 20));

watch([query, category], () => {
  page.value = 1;
});
</script>
