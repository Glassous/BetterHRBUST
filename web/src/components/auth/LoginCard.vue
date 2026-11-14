<template>
  <div :class="[embedded ? '' : 'p-7 sm:p-8 rounded-[var(--radius-card)] bg-[var(--bg-surface)] border border-[var(--border-hairline)] shadow-[var(--shadow-float)]']">
    <div class="mb-6 text-center">
      <div class="inline-flex items-center justify-center w-14 h-14 rounded-2xl overflow-hidden border border-[var(--border-hairline)] bg-[var(--bg-subtle)] p-1 mb-3.5 mx-auto">
        <img :src="logoUrl" alt="HRBUST" class="w-full h-full object-cover rounded-xl" />
      </div>
      <h2 class="text-xl font-semibold text-[var(--ink-primary)] tracking-tight">哈理工教务在线登录</h2>
      <p class="text-[13px] text-[var(--ink-muted)] mt-1.5">连接 http://jwzx.hrbust.edu.cn/academic · 真实账号数据直达</p>
    </div>

    <!-- Current Session Notice if already logged in -->
    <div
      v-if="isLoggedIn"
      class="mb-5 p-3.5 rounded-[var(--radius-panel)] border border-[var(--border-hairline)] bg-[var(--bg-subtle)] text-[13px] flex items-center justify-between gap-2"
    >
      <div class="flex items-center gap-2.5 min-w-0">
        <span class="w-2 h-2 rounded-full bg-[var(--sem-success)] shrink-0" />
        <span class="truncate font-medium text-[var(--ink-primary)] num-tabular">
          当前用户：{{ userProfile.realName || studentNumber }} ({{ studentNumber }})
        </span>
      </div>
      <span class="text-xs text-[var(--ink-muted)] shrink-0">重新认证或换号</span>
    </div>

    <!-- Session Expired Notice -->
    <div
      v-if="showLoginPageExpiredHint && !isLoggedIn"
      class="mb-5 p-3.5 rounded-[var(--radius-panel)] border border-[var(--sem-warning-border)] bg-[var(--sem-warning-soft)] text-[var(--ink-primary)] text-[13px] flex items-start gap-2.5"
    >
      <span class="w-2 h-2 rounded-full bg-[var(--sem-warning)] shrink-0 mt-1.5" />
      <div class="min-w-0">
        <div class="font-semibold">登录状态已失效</div>
        <div class="text-xs text-[var(--sem-warning)] mt-0.5 leading-relaxed">
          本地缓存数据仍可离线查看，重新登录后即可手动刷新。
        </div>
      </div>
    </div>

    <!-- Error Alert -->
    <div
      v-if="errorMessage"
      class="mb-5 p-3.5 rounded-[var(--radius-panel)] border border-[var(--sem-danger-border)] bg-[var(--sem-danger-soft)] text-[var(--sem-danger)] text-[13px] flex items-center gap-2 animate-shake"
    >
      <Icon name="warning" customClass="w-4 h-4 shrink-0" />
      <span class="flex-1">{{ errorMessage }}</span>
    </div>

    <!-- Form -->
    <form @submit.prevent="handleLoginSubmit" class="space-y-4.5">
      <!-- Username -->
      <div>
        <label class="block text-[13px] font-medium text-[var(--ink-secondary)] mb-1.5">学号</label>
        <UiInput
          v-model="form.username"
          @blur="prepareAccount"
          placeholder="请输入教务学号 (如 2401234567)"
          autocomplete="username"
          clearable
          :disabled="isLoggingIn"
        >
          <template #prefix>
            <Icon name="profile" customClass="w-4 h-4" />
          </template>
        </UiInput>
      </div>

      <!-- Password -->
      <div>
        <label class="block text-[13px] font-medium text-[var(--ink-secondary)] mb-1.5">密码</label>
        <div class="relative">
          <UiInput
            v-model="form.password"
            :type="showPassword ? 'text' : 'password'"
            placeholder="请输入教务在线密码"
            autocomplete="current-password"
            :disabled="isLoggingIn"
          >
            <template #prefix>
              <Icon name="lock" customClass="w-4 h-4" />
            </template>
            <template #suffix>
              <button
                type="button"
                class="p-1 text-[var(--ink-muted)] hover:text-[var(--ink-primary)] cursor-pointer transition-colors"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              >
                <Icon :name="showPassword ? 'eye-off' : 'eye'" customClass="w-4 h-4" />
              </button>
            </template>
          </UiInput>
        </div>
      </div>

      <!-- Captcha -->
      <div>
        <label class="block text-[13px] font-medium text-[var(--ink-secondary)] mb-1.5">验证码</label>
        <div class="flex items-center gap-3">
          <div class="flex-1">
            <UiInput
              v-model="form.captcha"
              placeholder="4 位验证码"
              maxlength="4"
              :disabled="isLoggingIn"
            >
              <template #prefix>
                <Icon name="shield" customClass="w-4 h-4" />
              </template>
            </UiInput>
          </div>

          <!-- Captcha image container -->
          <div
            class="relative h-[42px] w-30 bg-[var(--bg-subtle)] rounded-[var(--radius-control)] border border-[var(--border-hairline)] hover:border-[var(--border-strong)] overflow-hidden flex items-center justify-center cursor-pointer select-none transition-colors"
            title="点击更换验证码"
            @click="refreshCaptcha"
          >
            <img
              v-if="captchaImgUrl"
              :src="captchaImgUrl"
              alt="验证码"
              class="w-full h-full object-cover transition-opacity"
              :class="{ 'opacity-40': captchaLoading }"
              @load="captchaLoading = false"
              @error="onCaptchaLoadError"
            />
            <div
              v-if="captchaLoading"
              class="absolute inset-0 flex items-center justify-center bg-[var(--bg-backdrop)]"
            >
              <Icon name="refresh" customClass="w-4 h-4 animate-spin text-[var(--ink-primary)]" />
            </div>
            <div
              v-if="captchaError"
              class="text-xs text-[var(--sem-danger)] font-medium px-1 text-center"
            >
              加载失败点击重试
            </div>
          </div>
        </div>
      </div>

      <!-- Remember & Network info -->
      <div class="flex items-center justify-between text-[13px] pt-1">
        <label
          class="flex items-center gap-2 text-[var(--ink-secondary)] cursor-pointer select-none"
          title="密码混淆后仅保存在本机，下次登录自动填入"
        >
          <input
            type="checkbox"
            v-model="rememberMe"
            class="w-4 h-4 rounded border-[var(--border-strong)] accent-[var(--accent)] cursor-pointer"
          />
          <span>记住密码</span>
        </label>
        <span class="text-xs text-[var(--ink-muted)]">校园网 / VPN 环境直连</span>
      </div>

      <!-- Submit button -->
      <div class="pt-2">
        <UiButton
          type="submit"
          variant="primary"
          block
          size="lg"
          :loading="isLoggingIn"
          :disabled="!canSubmit"
        >
          {{ isLoggingIn ? '正在验证身份...' : (isLoggedIn ? '重新认证 / 切换登录' : '登录教务在线') }}
        </UiButton>
      </div>
    </form>

    <!-- Footer Security Notice -->
    <div class="mt-6 pt-4 border-t border-[var(--border-hairline)] text-xs text-[var(--ink-muted)] text-center leading-relaxed">
      登录信息仅通过本地代理发送至哈尔滨理工大学教务系统，会话 Cookie 仅存储于当前浏览器中，无模拟假数据，无云端中转。
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue';
import UiInput from '@/components/ui/UiInput.vue';
import UiButton from '@/components/ui/UiButton.vue';
import Icon from '@/components/icons/Icon.vue';
import logoUrl from '@/assets/HRBUST.png';
import { useSession } from '@/composables/useSession.js';
import { academicApi } from '@/services/academic/api.js';
import { readSavedCredential, clearCredential } from '@/services/savedCredential.js';

const props = defineProps({
  embedded: {
    type: Boolean,
    default: false
  }
});

const emit = defineEmits(['success']);

const { isLoggingIn, isPreparingAccount, loginError, login, prepareLoginAccount, studentNumber, isLoggedIn, userProfile, showLoginPageExpiredHint } =
  useSession();

// 上次登录成功后保存的账号密码（"记住密码"），用于重新登录时自动填入
const savedCredential = readSavedCredential();

const showPassword = ref(false);
const rememberMe = ref(true);
const captchaImgUrl = ref('');
const captchaLoading = ref(false);
const captchaError = ref(false);
const errorMessage = ref('');

const form = reactive({
  username: savedCredential?.username || studentNumber.value || '',
  password: savedCredential?.password || '',
  captcha: ''
});

// 取消勾选立即清除本地保存的密码，避免下次打开仍然自动填入
watch(rememberMe, (value) => {
  if (!value) clearCredential();
});

const canSubmit = computed(() => {
  return !isPreparingAccount.value && !captchaLoading.value && !captchaError.value && form.username.trim() && form.password.trim() && form.captcha.trim().length === 4;
});

async function prepareAccount() {
  try { if (await prepareLoginAccount(form.username)) await refreshCaptcha(); }
  catch (error) { errorMessage.value = error.message; captchaImgUrl.value = ''; captchaError.value = true; captchaLoading.value = false; }
}

async function refreshCaptcha() {
  captchaLoading.value = true;
  captchaError.value = false;
  captchaImgUrl.value = '';
  form.captcha = '';
  try {
    await prepareLoginAccount(form.username);
    captchaImgUrl.value = academicApi.getCaptchaUrl();
  } catch (error) {
    errorMessage.value = error.message || '无法准备新账号的验证码，请重试';
    captchaLoading.value = false;
    captchaError.value = true;
  }
}

function onCaptchaLoadError() {
  captchaLoading.value = false;
  captchaError.value = true;
}

async function handleLoginSubmit() {
  if (!canSubmit.value || isLoggingIn.value) return;
  errorMessage.value = '';

  const res = await login({
    username: form.username,
    password: form.password,
    captcha: form.captcha,
    remember: rememberMe.value
  });

  if (res.success) {
    emit('success');
  } else {
    errorMessage.value = res.message || '登录失败，请检查账号密码及验证码';
    refreshCaptcha();
  }
}

onMounted(() => {
  refreshCaptcha();
});
</script>

<style scoped>
@keyframes shake {
  0%, 100% { transform: translateX(0); }
  20%, 60% { transform: translateX(-4px); }
  40%, 80% { transform: translateX(4px); }
}
.animate-shake {
  animation: shake 0.3s ease-in-out;
}
</style>
