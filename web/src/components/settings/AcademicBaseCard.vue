<template>
  <UiCard title="教务地址" subtitle="默认指向官方教务在线；填写其他 URP 部署即可切换，随时可恢复默认">
    <template #header-action>
      <span
        class="text-[12px] px-2 py-0.5 rounded-[var(--radius-badge)]"
        :class="
          isOfficial
            ? 'bg-[var(--bg-hover)] text-[var(--ink-muted)]'
            : 'bg-[var(--sem-success)]/12 text-[var(--sem-success)]'
        "
      >
        {{ isOfficial ? '官方默认' : '自定义' }}
      </span>
    </template>

    <div class="space-y-5">
      <!-- 当前生效地址 -->
      <div class="rounded-[var(--radius-panel)] border border-[var(--border-hairline)] bg-[var(--bg-subtle)] p-3.5">
        <div class="text-[12px] text-[var(--ink-muted)]">当前生效地址</div>
        <div class="mt-1 font-mono text-[13px] text-[var(--ink-primary)] break-all">{{ effective }}</div>
      </div>

      <!-- 地址输入 -->
      <div>
        <label :for="baseUrlId" class="block text-[13px] font-medium text-[var(--ink-primary)] mb-1.5">自定义地址</label>
        <UiInput
          :id="baseUrlId"
          v-model="form.baseUrl"
          spellcheck="false"
          autocorrect="off"
          autocapitalize="none"
          placeholder="https://jwzx.example.edu.cn"
          @enter="submit"
        />
        <p class="text-[12px] text-[var(--ink-muted)] mt-1.5 leading-relaxed">
          可粘贴主页链接（如 <code class="text-[11.5px]">https://jwzx.example.edu.cn/homepage/index.do</code>）或只填域名，
          会自动归一化为 <code class="text-[11.5px]">协议://主机/academic/</code>；协议、端口与路径前缀不会被保留。
        </p>
      </div>

      <p v-if="errorMessage" class="text-[12.5px] text-[var(--sem-danger)]">{{ errorMessage }}</p>
      <p v-else-if="successMessage" class="text-[12.5px] text-[var(--sem-success)]">{{ successMessage }}</p>
      <p v-if="hasInvalidStored" class="text-[12.5px] text-[var(--sem-warning)]">
        已保存的地址无法解析（{{ invalidReason }}），当前已回退官方默认，请重新填写或恢复默认。
      </p>

      <!-- 操作 -->
      <div class="flex flex-wrap items-center gap-2">
        <UiButton variant="primary" size="sm" :disabled="!form.baseUrl.trim()" @click="submit">保存并生效</UiButton>
        <UiButton variant="outline" size="sm" @click="reset">恢复官方默认</UiButton>
        <span class="text-[11.5px] text-[var(--ink-muted)]">保存后立即生效，无需重启</span>
      </div>

      <!-- 生效方式说明 -->
      <div class="pt-1 border-t border-[var(--border-hairline)] space-y-2">
        <p class="text-[12px] text-[var(--ink-muted)] leading-relaxed">
          <template v-if="mode === 'desktop'">
            桌面端由本地反代转发到该地址，登录会话与 302 跳转都留在本机，可与 TUI 共用同一份配置。
          </template>
          <template v-else-if="mode === 'userscript'">
            油猴脚本通过扩展通道请求该地址，可跨域访问；登录 Cookie 仍由浏览器与教务站点直接交换。
          </template>
          <template v-else>
            网页版会直接请求该地址：目标站点允许跨域时浏览器直连；被浏览器拦下时自动改由本地代理转发
            （<code class="text-[11.5px]">npm run dev</code> / <code class="text-[11.5px]">npm run preview</code>
            内置，换地址无需重启），登录会话与 302 跳转照常可用。
          </template>
        </p>
        <p class="text-[12px] text-[var(--ink-muted)] leading-relaxed">
          切换地址不会删除已保存的登录会话与缓存；但如果新站点不认旧会话，需要重新登录一次。
        </p>
      </div>
    </div>
  </UiCard>
</template>

<script setup>
import { computed, reactive, ref, useId } from 'vue';
import UiCard from '@/components/ui/UiCard.vue';
import UiInput from '@/components/ui/UiInput.vue';
import UiButton from '@/components/ui/UiButton.vue';
import { useAcademicBase } from '@/composables/useAcademicBase.js';
import { isDesktopApp, isUserscript } from '@/utils/platform.js';

const baseUrlId = useId();
const { effective, isOfficial, invalidReason, hasInvalidStored, save, reset: resetBase } = useAcademicBase();

const mode = computed(() => (isDesktopApp ? 'desktop' : isUserscript ? 'userscript' : 'web'));

const form = reactive({ baseUrl: '' });
const errorMessage = ref('');
const successMessage = ref('');

function submit() {
  errorMessage.value = '';
  successMessage.value = '';
  try {
    const state = save(form.baseUrl);
    form.baseUrl = '';
    successMessage.value = state.base ? '已切换到自定义教务地址' : '已恢复官方教务地址';
  } catch (err) {
    errorMessage.value = String(err?.message || err);
  }
}

function reset() {
  errorMessage.value = '';
  resetBase();
  form.baseUrl = '';
  successMessage.value = '已恢复官方教务地址';
}
</script>
