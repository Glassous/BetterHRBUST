<template>
  <div class="space-y-6 max-w-3xl">
    <!-- 页头 -->
    <div
      class="rounded-[var(--radius-card)] border border-[var(--border-hairline)] bg-[var(--bg-surface)] p-5 sm:p-6"
    >
      <div class="flex items-start gap-4">
        <div
          class="w-12 h-12 rounded-2xl bg-[var(--accent-soft)] text-[var(--accent)] border border-[var(--accent-border)] flex items-center justify-center shrink-0"
        >
          <Icon name="message" customClass="w-5 h-5" />
        </div>
        <div class="min-w-0">
          <h1 class="text-xl font-semibold text-[var(--ink-primary)] tracking-tight">意见反馈</h1>
          <p class="text-[13px] text-[var(--ink-secondary)] mt-1.5 leading-relaxed">
            提交使用中遇到的问题、功能建议或数据偏差。反馈只保存你写下的文字，
            系统不会附带学号、成绩、课表或任何其他学生信息。
          </p>
        </div>
      </div>
    </div>

    <!-- 未登录：门控 -->
    <UiCard v-if="!isLoggedIn">
      <div class="flex items-start gap-4">
        <div
          class="w-11 h-11 rounded-xl bg-[var(--sem-warning-soft)] border border-[var(--sem-warning-border)] text-[var(--sem-warning)] flex items-center justify-center shrink-0"
        >
          <Icon name="lock" customClass="w-5 h-5" />
        </div>
        <div class="min-w-0 flex-1">
          <div class="text-[15px] font-semibold text-[var(--ink-primary)]">请先登录教务在线</div>
          <p class="text-[13px] text-[var(--ink-secondary)] mt-1 leading-relaxed">
            为了确认反馈来自校内使用者，提交前需要先登录教务在线。登录信息仅保存在你自己的浏览器中，
            不会随反馈上传到服务器。
          </p>
          <div class="mt-4">
            <UiButton variant="primary" size="sm" @click="openLoginModal()">前往登录</UiButton>
          </div>
        </div>
      </div>
    </UiCard>

    <!-- 提交成功 -->
    <UiCard v-else-if="submitted">
      <div class="flex items-start gap-4">
        <div
          class="w-11 h-11 rounded-xl bg-[var(--sem-success-soft)] border border-[var(--sem-success-border)] text-[var(--sem-success)] flex items-center justify-center shrink-0"
        >
          <Icon name="check-circle" customClass="w-5 h-5" />
        </div>
        <div class="min-w-0 flex-1">
          <div class="text-[15px] font-semibold text-[var(--ink-primary)]">反馈已收到，感谢你的反馈</div>
          <p class="text-[13px] text-[var(--ink-secondary)] mt-1 leading-relaxed num-tabular">
            提交时间 {{ submittedAt }}。已保存的字段：反馈正文、提交时间与阅读状态{{ submittedSummary }}。
            服务器不保存任何可以定位到你本人的身份信息。
          </p>
          <div class="mt-4 flex items-center gap-2.5">
            <UiButton variant="primary" size="sm" @click="resetForm">再写一条</UiButton>
            <UiButton variant="ghost" size="sm" @click="goBack">返回概览</UiButton>
          </div>
        </div>
      </div>
    </UiCard>

    <!-- 反馈表单 -->
    <UiCard v-else title="写下你的反馈">
      <form class="space-y-5" @submit.prevent="handleSubmit">
        <div>
          <label class="block text-[13px] font-medium text-[var(--ink-secondary)] mb-2">
            反馈内容
          </label>
          <UiTextarea
            v-model="content"
            :rows="8"
            placeholder="例如：成绩页在筛选「限选」后，切换学期会保留上一次的筛选条件，希望可以自动重置。"
            :custom-class="contentOverflow ? '!border-[var(--sem-danger)]' : ''"
          >
            <template #hint>
              <span>无需标题，直接描述问题或建议即可</span>
              <span class="num-tabular" :class="contentOverflow ? 'text-[var(--sem-danger)] font-medium' : ''">{{ contentLength }} / {{ limits.maxContent }}</span>
            </template>
          </UiTextarea>
        </div>

        <!-- 实名发布 -->
        <div
          class="rounded-[var(--radius-panel)] border p-4.5 transition-colors duration-150"
          :class="realNameEnabled
            ? 'bg-[var(--bg-subtle)] border-[var(--border-strong)]'
            : 'bg-[var(--bg-subtle)]/50 border-[var(--border-hairline)]'"
        >
          <div class="flex items-start justify-between gap-4">
            <div class="min-w-0">
              <div class="text-[15px] font-semibold text-[var(--ink-primary)]">实名发布</div>
              <p class="text-[13px] text-[var(--ink-muted)] mt-1 leading-relaxed">
                默认关闭。开启后本条反馈会带上你的姓名，便于必要时与你核对问题；
                每次进入本页都需要重新手动开启。
              </p>
            </div>
            <UiSwitch v-model="realNameEnabled" />
          </div>

          <div v-if="realNameEnabled" class="mt-4 pt-4 border-t border-[var(--border-hairline)] panel-expand">
            <label class="block text-[13px] font-medium text-[var(--ink-secondary)] mb-2">
              姓名
            </label>
            <UiInput
              v-if="loadedRealName"
              :model-value="loadedRealName"
              readonly
              custom-class="opacity-80"
            >
              <template #prefix>
                <Icon name="profile" customClass="w-4 h-4" />
              </template>
            </UiInput>
            <UiInput
              v-else
              v-model="manualName"
              placeholder="请输入姓名"
              :maxlength="limits.maxSignature"
            >
              <template #prefix>
                <Icon name="profile" customClass="w-4 h-4" />
              </template>
            </UiInput>
            <p class="text-[13px] text-[var(--ink-muted)] mt-2 leading-relaxed">
              {{ loadedRealName
                ? '姓名取自当前教务登录缓存，仅随本条反馈一并提交，不会写入任何账号档案。'
                : '暂时无法从教务缓存读取姓名，可手动填写；留空将按匿名提交。' }}
            </p>
          </div>
        </div>

        <!-- 联系方式 -->
        <div>
          <label class="block text-[13px] font-medium text-[var(--ink-secondary)] mb-2">
            联系方式 <span class="text-[var(--ink-muted)] font-normal">（选填）</span>
          </label>
          <UiInput
            v-model="contact"
            placeholder="邮箱、手机号或 QQ / 微信，便于我们回复你"
            clearable
            :maxlength="limits.maxContact"
          >
            <template #prefix>
              <Icon name="mail" customClass="w-4 h-4" />
            </template>
          </UiInput>
        </div>

        <!-- 错误提示 -->
        <div
          v-if="errorMessage"
          class="p-3.5 rounded-[var(--radius-panel)] border border-[var(--sem-danger-border)] bg-[var(--sem-danger-soft)] text-[var(--sem-danger)] text-[13px] flex items-start gap-2.5"
        >
          <Icon name="alert-triangle" customClass="w-4 h-4 shrink-0 mt-0.5" />
          <span class="flex-1 leading-relaxed">{{ errorMessage }}</span>
        </div>

        <!-- 提交 -->
        <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pt-1">
          <p class="text-[13px] text-[var(--ink-muted)] leading-relaxed">
            提交即表示你同意我们保存上述文本用于改进产品。
          </p>
          <UiButton type="submit" variant="primary" :loading="submitting" :disabled="!canSubmit">
            <template #prefix>
              <Icon v-if="!submitting" name="send" customClass="w-4 h-4" />
            </template>
            {{ submitting ? '正在提交…' : '提交反馈' }}
          </UiButton>
        </div>
      </form>
    </UiCard>

    <!-- 隐私说明 -->
    <UiCard title="关于隐私">
      <ul class="text-[13px] text-[var(--ink-secondary)] space-y-2.5 leading-relaxed">
        <li class="flex items-start gap-2.5">
          <span class="w-1.5 h-1.5 rounded-full bg-[var(--sem-success)] mt-2 shrink-0" />
          <span>服务器只保存：反馈正文、你选填的联系方式、实名时随反馈提交的姓名文本、提交时间与阅读状态。</span>
        </li>
        <li class="flex items-start gap-2.5">
          <span class="w-1.5 h-1.5 rounded-full bg-[var(--sem-danger)] mt-2 shrink-0" />
          <span>服务器不保存：学号、教务内部 ID、成绩、课表、Cookie 等任何教务数据，也不记录来源 IP 与设备信息。</span>
        </li>
        <li class="flex items-start gap-2.5">
          <span class="w-1.5 h-1.5 rounded-full bg-[var(--ink-muted)] mt-2 shrink-0" />
          <span>反馈不会与你的教务账号建立任何关联，管理端只能阅读文本内容。</span>
        </li>
      </ul>
    </UiCard>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import UiCard from '@/components/ui/UiCard.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiInput from '@/components/ui/UiInput.vue';
import UiSwitch from '@/components/ui/UiSwitch.vue';
import UiTextarea from '@/components/ui/UiTextarea.vue';
import Icon from '@/components/icons/Icon.vue';
import { useSession } from '@/composables/useSession.js';
import { useToast } from '@/composables/useToast.js';
import { FEEDBACK_LIMITS, submitFeedback, textLength } from '@/services/feedback.js';

const limits = FEEDBACK_LIMITS;
const { isLoggedIn, userProfile, openLoginModal, navigateTo } = useSession();
const { showToast } = useToast();

const content = ref('');
const contact = ref('');
// 实名开关：每次进入页面都从关闭状态开始，必须手动开启
const realNameEnabled = ref(false);
const manualName = ref('');
const submitting = ref(false);
const errorMessage = ref('');
const submitted = ref(false);
const submittedAt = ref('');
const submittedSummary = ref('');

const loadedRealName = computed(() => String(userProfile.realName || '').trim());
const contentLength = computed(() => textLength(content.value));
const contentOverflow = computed(() => contentLength.value > limits.maxContent);
const contentReady = computed(() => {
  const length = contentLength.value;
  return length >= limits.minContent && length <= limits.maxContent;
});
const signatureReady = computed(() => {
  if (!realNameEnabled.value) return true;
  return textLength(loadedRealName.value || manualName.value) > 0;
});
const canSubmit = computed(() => contentReady.value && signatureReady.value && !submitting.value);

function resetForm() {
  content.value = '';
  contact.value = '';
  realNameEnabled.value = false;
  manualName.value = '';
  errorMessage.value = '';
  submitted.value = false;
  submittedAt.value = '';
  submittedSummary.value = '';
}

function goBack() {
  navigateTo('dashboard');
}

function formatTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '刚刚';
  return date.toLocaleString('zh-CN', { hour12: false });
}

async function handleSubmit() {
  if (!canSubmit.value) {
    if (!contentReady.value) {
      errorMessage.value = `反馈内容需在 ${limits.minContent} - ${limits.maxContent} 字之间`;
      return;
    }
    errorMessage.value = '已开启实名发布，请先填写姓名或关闭该开关';
    return;
  }

  submitting.value = true;
  errorMessage.value = '';
  try {
    const result = await submitFeedback({
      content: content.value,
      contact: contact.value,
      signature: realNameEnabled.value ? (loadedRealName.value || manualName.value) : '',
      anonymous: !realNameEnabled.value
    });

    submittedAt.value = formatTime(result?.createdAt || Date.now());
    const extras = [];
    if (contact.value.trim()) extras.push('联系方式');
    if (realNameEnabled.value && (loadedRealName.value || manualName.value).trim()) extras.push('姓名文本');
    submittedSummary.value = extras.length ? `、${extras.join('、')}` : '';
    submitted.value = true;
    // 表单已提交，重置内容，避免误重复提交
    content.value = '';
    contact.value = '';
    realNameEnabled.value = false;
    manualName.value = '';

    showToast({ title: '反馈已提交', message: '感谢你的反馈，我们会尽快阅读。', type: 'success' });
  } catch (error) {
    errorMessage.value = error?.message || '提交失败，请稍后重试';
  } finally {
    submitting.value = false;
  }
}

onMounted(() => {
  // 强制每次进入页面实名开关都处于关闭状态，不读取任何历史偏好
  realNameEnabled.value = false;
  manualName.value = '';
});
</script>
