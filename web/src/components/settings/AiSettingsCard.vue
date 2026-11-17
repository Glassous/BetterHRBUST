<template>
  <UiCard title="AI 对话" subtitle="用自然语言查询本地缓存（只读工具，数字与页面一致）">
    <template #header-action>
      <span
        class="text-[12px] px-2 py-0.5 rounded-[var(--radius-badge)]"
        :class="
          ready
            ? 'bg-[var(--sem-success)]/12 text-[var(--sem-success)]'
            : 'bg-[var(--bg-hover)] text-[var(--ink-muted)]'
        "
      >
        {{ ready ? '已就绪' : '未就绪' }}
      </span>
    </template>

    <div class="space-y-5">
      <!-- 主开关 -->
      <div class="flex items-start justify-between gap-4">
        <div class="min-w-0">
          <div id="ai-enabled-label" class="text-[15px] font-semibold text-[var(--ink-primary)]">AI 对话</div>
          <div class="text-[13px] text-[var(--ink-muted)] mt-1 leading-relaxed">
            打开后按 <kbd class="px-1.5 py-0.5 rounded bg-[var(--bg-hover)] text-[11.5px]">Ctrl</kbd> +
            <kbd class="px-1.5 py-0.5 rounded bg-[var(--bg-hover)] text-[11.5px]">J</kbd>
            或点侧栏底部用户信息右侧的 AI 按钮；打开时会同时确认数据出网。
          </div>
        </div>
        <UiSwitch :modelValue="form.enabled" aria-labelledby="ai-enabled-label" @update:modelValue="requestToggleEnabled" />
      </div>

      <!-- 出网确认 -->
      <div
        v-if="confirmOpen"
        class="rounded-[var(--radius-panel)] border border-[var(--sem-warning)]/45 bg-[var(--sem-warning)]/8 p-3.5 space-y-2.5"
      >
        <p class="text-[13px] leading-relaxed text-[var(--ink-primary)]">
          打开 AI 对话后，你的提问与查询结果（学籍中的证件号、手机号、邮箱、地址已打码）会发送到你配置的服务商：
          <strong>{{ provider }}</strong>
        </p>
        <div class="flex items-center gap-2">
          <UiButton variant="primary" size="sm" @click="confirmEnable">确认开启</UiButton>
          <UiButton variant="ghost" size="sm" @click="confirmOpen = false">取消</UiButton>
        </div>
      </div>

      <!-- 允许数据出网 -->
      <div class="flex items-center justify-between gap-4">
        <div>
          <div id="ai-consent-label" class="text-[14px] font-medium text-[var(--ink-primary)]">允许数据出网</div>
          <div class="text-[12.5px] text-[var(--ink-muted)] mt-0.5">关闭后立即停止发送任何内容</div>
        </div>
        <UiSwitch :modelValue="form.consent" aria-labelledby="ai-consent-label" @update:modelValue="save({ consent: $event })" />
      </div>

      <div class="grid gap-4 sm:grid-cols-2">
        <div class="sm:col-span-2">
          <label :for="baseUrlId" class="block text-[13px] font-medium text-[var(--ink-primary)] mb-1.5">接口地址</label>
          <UiInput
            :id="baseUrlId"
            v-model="form.base_url"
            spellcheck="false"
            autocorrect="off"
            autocapitalize="none"
            placeholder="https://api.deepseek.com/v1"
            @enter="saveForm"
          />
          <p class="text-[12px] text-[var(--ink-muted)] mt-1.5 leading-relaxed">
            OpenAI 兼容服务的 /v1 根地址。常见值：DeepSeek
            <code class="text-[11.5px]">https://api.deepseek.com/v1</code>、Ollama
            <code class="text-[11.5px]">http://127.0.0.1:11434/v1</code>。
          </p>
        </div>

        <div>
          <label :for="modelId" class="block text-[13px] font-medium text-[var(--ink-primary)] mb-1.5">模型</label>
          <UiInput :id="modelId" v-model="form.model" spellcheck="false" autocorrect="off" autocapitalize="none" placeholder="deepseek-flash" @enter="saveForm" />
        </div>

        <div>
          <label :for="apiKeyId" class="block text-[13px] font-medium text-[var(--ink-primary)] mb-1.5">API Key</label>
          <UiInput
            :id="apiKeyId"
            v-model="form.apiKey"
            type="password"
            :placeholder="view?.hasKey ? view.apiKeyMasked : '未设置'"
            @enter="saveForm"
          />
          <p class="text-[12px] text-[var(--ink-muted)] mt-1.5 leading-relaxed">
            {{ keyHint }}
          </p>
        </div>

        <div>
          <label class="block text-[13px] font-medium text-[var(--ink-primary)] mb-1.5">温度</label>
          <UiTabs
            :items="temperatureItems"
            :modelValue="form.temperature"
            @update:modelValue="save({ temperature: $event })"
          />
        </div>

        <div>
          <label class="block text-[13px] font-medium text-[var(--ink-primary)] mb-1.5">工具轮次上限</label>
          <UiTabs
            :items="roundItems"
            :modelValue="form.max_tool_rounds"
            @update:modelValue="save({ max_tool_rounds: $event })"
          />
        </div>

        <div>
          <label class="block text-[13px] font-medium text-[var(--ink-primary)] mb-1.5">请求超时</label>
          <UiTabs
            :items="timeoutItems"
            :modelValue="form.timeout_seconds"
            @update:modelValue="save({ timeout_seconds: $event })"
          />
        </div>

        <div class="flex items-center justify-between gap-4">
          <div class="min-w-0">
            <div id="ai-send-profile-label" class="text-[13.5px] font-medium text-[var(--ink-primary)]">发送学籍</div>
            <div class="text-[12px] text-[var(--ink-muted)] mt-0.5 leading-relaxed">
              关闭时模型看不到学籍；即使打开，证件号与手机号也已打码
            </div>
          </div>
          <UiSwitch :modelValue="form.send_profile" aria-labelledby="ai-send-profile-label" @update:modelValue="save({ send_profile: $event })" />
        </div>
      </div>

      <!-- 状态与操作 -->
      <div class="pt-1 border-t border-[var(--border-hairline)] space-y-3">
        <p v-if="!ready" class="text-[12.5px] text-[var(--ink-muted)]">{{ readiness.reason }}</p>
        <p v-else class="text-[12.5px] text-[var(--sem-success)]">已就绪：可以向 {{ provider }} 提问</p>
        <p v-if="errorMessage" class="text-[12.5px] text-[var(--sem-danger)]">{{ errorMessage }}</p>

        <div class="flex flex-wrap items-center gap-2">
          <UiButton variant="primary" size="sm" :disabled="saving" @click="saveForm">
            {{ saving ? '保存中…' : '保存' }}
          </UiButton>
          <UiButton v-if="view?.hasKey" variant="ghost" size="sm" :disabled="saving" @click="clearKey">
            清除 API Key
          </UiButton>
          <UiButton variant="ghost" size="sm" :disabled="saving" @click="resetAll">
            恢复默认
          </UiButton>
          <span v-if="view?.path" class="text-[11.5px] text-[var(--ink-muted)] truncate">
            配置：{{ view.path }}
          </span>
        </div>
        <p class="text-[11.5px] text-[var(--ink-muted)] leading-relaxed">
          隐私边界：只有提问与工具返回的查询结果会发送；证件号、手机号、邮箱、地址在离开进程前已经打码；对话不落盘。
        </p>
      </div>
    </div>
  </UiCard>
</template>

<script setup>
import { computed, reactive, ref, useId, watch } from 'vue';
import UiCard from '@/components/ui/UiCard.vue';
import UiSwitch from '@/components/ui/UiSwitch.vue';
import UiInput from '@/components/ui/UiInput.vue';
import UiTabs from '@/components/ui/UiTabs.vue';
import UiButton from '@/components/ui/UiButton.vue';
import { useAiChat } from '@/composables/useAiChat.js';
import { DEFAULT_AI_CONFIG, TEMPERATURE_OPTIONS, TIMEOUT_OPTIONS, TOOL_ROUND_OPTIONS } from '@/services/ai/config.js';

const { view, readiness, ready, provider, applyConfig, clearConfig, refreshConfig } = useAiChat();

const baseUrlId = useId();
const modelId = useId();
const apiKeyId = useId();

const saving = ref(false);
const errorMessage = ref('');
const confirmOpen = ref(false);

const form = reactive({
  enabled: false,
  consent: false,
  base_url: DEFAULT_AI_CONFIG.base_url,
  model: DEFAULT_AI_CONFIG.model,
  apiKey: '',
  temperature: DEFAULT_AI_CONFIG.temperature,
  max_tool_rounds: DEFAULT_AI_CONFIG.max_tool_rounds,
  timeout_seconds: DEFAULT_AI_CONFIG.timeout_seconds,
  send_profile: false
});

const temperatureItems = TEMPERATURE_OPTIONS.map(value => ({ label: String(value), value }));
const roundItems = TOOL_ROUND_OPTIONS.map(value => ({ label: String(value), value }));
const timeoutItems = TIMEOUT_OPTIONS.map(value => ({ label: `${value}s`, value }));

const keyHint = computed(() => {
  if (view.value?.mode === 'desktop') {
    return '保存在本机 ai-config.json（与 storage.json 分开），不会随缓存被页面全量读取';
  }
  return '浏览器没有等价的文件权限，Key 会以明文保存在本地存储，请仅在可信设备上使用';
});

function syncForm(source) {
  const config = source?.config || DEFAULT_AI_CONFIG;
  form.enabled = !!config.enabled;
  form.consent = !!config.consent;
  form.base_url = config.base_url;
  form.model = config.model;
  form.apiKey = '';
  form.temperature = config.temperature;
  form.max_tool_rounds = config.max_tool_rounds;
  form.timeout_seconds = config.timeout_seconds;
  form.send_profile = !!config.send_profile;
}

watch(view, value => syncForm(value), { immediate: true });

async function save(patch) {
  saving.value = true;
  errorMessage.value = '';
  try {
    const next = await applyConfig(patch);
    syncForm(next);
  } catch (err) {
    errorMessage.value = String(err?.message || err);
  } finally {
    saving.value = false;
  }
}

/** 表单整体保存：text 字段只有非空才覆盖，key 留空表示不修改 */
function saveForm() {
  const patch = {
    base_url: form.base_url.trim(),
    model: form.model.trim(),
    temperature: Number(form.temperature),
    max_tool_rounds: Number(form.max_tool_rounds),
    timeout_seconds: Number(form.timeout_seconds),
    send_profile: !!form.send_profile
  };
  if (form.apiKey && form.apiKey.trim()) patch.api_key = form.apiKey.trim();
  return save(patch);
}

function requestToggleEnabled(next) {
  if (!next) {
    save({ enabled: false });
    return;
  }
  confirmOpen.value = true;
}

function confirmEnable() {
  confirmOpen.value = false;
  save({ enabled: true, consent: true });
}

function clearKey() {
  save({ clear_key: true });
}

async function resetAll() {
  saving.value = true;
  errorMessage.value = '';
  try {
    const next = await clearConfig();
    syncForm(next);
    await refreshConfig();
  } catch (err) {
    errorMessage.value = String(err?.message || err);
  } finally {
    saving.value = false;
  }
}
</script>
