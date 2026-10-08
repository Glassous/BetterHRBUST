<template>
  <div class="max-w-xl mx-auto py-4 sm:py-8">
    <UiCard customClass="p-4 sm:p-8 shadow-sm">
      <div class="space-y-6">
        <!-- Brand Header -->
        <div class="text-center space-y-3">
          <div class="inline-flex items-center justify-center w-20 h-20 rounded-full overflow-hidden shadow-md bg-zinc-100 dark:bg-zinc-800 ring-4 ring-zinc-200/60 dark:ring-zinc-700/60 mx-auto">
            <img
              :src="logoUrl"
              alt="HRBUST"
              class="w-full h-full object-cover rounded-full"
            />
          </div>
          <h1 class="text-2xl font-bold text-zinc-900 dark:text-zinc-100 tracking-tight">
            哈尔滨理工大学 · 教务在线登录
          </h1>
        </div>

        <!-- Current Logged In Notice (Direct enter button removed, area preserved) -->
        <div
          v-if="isLoggedIn"
          class="p-4 rounded-xl border border-emerald-500/20 bg-emerald-500/10 text-emerald-800 dark:text-emerald-300 text-xs flex items-center gap-3"
        >
          <span class="w-2.5 h-2.5 rounded-full bg-emerald-500 shrink-0" />
          <div class="min-w-0">
            <div class="font-bold text-sm">
              {{ userProfile.realName || studentNumber }}
              <span class="opacity-80 text-xs font-normal ml-1">({{ studentNumber }})</span>
            </div>
            <div class="text-[12px] text-emerald-600 dark:text-emerald-400 mt-0.5">
              当前已处于登录状态，在此可重新认证或切换学号
            </div>
          </div>
        </div>

        <!-- Error Alert -->
        <div
          v-if="errorMessage"
          class="p-3.5 rounded-xl border border-rose-500/20 bg-rose-500/10 text-rose-600 dark:text-rose-400 text-xs flex items-center gap-2.5 animate-shake"
        >
          <Icon name="warning" customClass="w-4 h-4 shrink-0" />
          <span class="flex-1">{{ errorMessage }}</span>
        </div>

        <!-- Login Form -->
        <form @submit.prevent="handleLoginSubmit" class="space-y-4">
          <!-- Student Number -->
          <div>
            <label class="block text-xs font-semibold text-zinc-700 dark:text-zinc-300 mb-1.5">
              教务学号
            </label>
            <UiInput
              v-model="form.username"
              placeholder="请输入您的学号 (例如 2023000001)"
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
            <label class="block text-xs font-semibold text-zinc-700 dark:text-zinc-300 mb-1.5">
              教务密码
            </label>
            <div class="relative">
              <UiInput
                v-model="form.password"
                :type="showPassword ? 'text' : 'password'"
                placeholder="请输入哈理工教务在线密码"
                autocomplete="current-password"
                :disabled="isLoggingIn"
              >
                <template #prefix>
                  <svg class="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                    <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
                    <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
                  </svg>
                </template>
                <template #suffix>
                  <button
                    type="button"
                    class="p-1 text-zinc-400 hover:text-zinc-600 dark:hover:text-zinc-200 cursor-pointer"
                    @click="showPassword = !showPassword"
                  >
                    <Icon :name="showPassword ? 'eye-off' : 'eye'" customClass="w-3.5 h-3.5" />
                  </button>
                </template>
              </UiInput>
            </div>
          </div>

          <!-- Captcha -->
          <div>
            <label class="block text-xs font-semibold text-zinc-700 dark:text-zinc-300 mb-1.5">
              安全验证码
            </label>
            <div class="flex items-center gap-3">
              <div class="flex-1">
                <UiInput
                  v-model="form.captcha"
                  placeholder="请输入 4 位验证码"
                  maxlength="4"
                  :disabled="isLoggingIn"
                >
                  <template #prefix>
                    <svg class="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                      <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
                    </svg>
                  </template>
                </UiInput>
              </div>

              <!-- Captcha Image Button -->
              <div
                class="relative h-[38px] w-28 bg-zinc-100 dark:bg-zinc-800 rounded-lg border border-zinc-200 dark:border-zinc-700 overflow-hidden flex items-center justify-center cursor-pointer select-none group"
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
                  class="absolute inset-0 flex items-center justify-center bg-zinc-900/10 dark:bg-zinc-100/10"
                >
                  <Icon name="refresh" customClass="w-4 h-4 animate-spin text-zinc-600 dark:text-zinc-300" />
                </div>
                <div
                  v-if="captchaError"
                  class="text-[11px] text-rose-500 font-medium px-1 text-center"
                >
                  点击重试
                </div>
              </div>
            </div>
          </div>

          <!-- Remember Me -->
          <div class="flex items-center text-xs pt-1">
            <label class="flex items-center gap-2 text-zinc-600 dark:text-zinc-400 cursor-pointer select-none">
              <input
                type="checkbox"
                v-model="rememberMe"
                class="rounded border-zinc-300 dark:border-zinc-700 text-zinc-900 focus:ring-0 cursor-pointer"
              />
              <span>记住学号</span>
            </label>
          </div>

          <!-- Submit Button -->
          <div class="pt-2">
            <UiButton
              type="submit"
              variant="primary"
              block
              size="lg"
              :loading="isLoggingIn"
              :disabled="!canSubmit"
            >
              {{ isLoggingIn ? '正在连接教务系统验证身份...' : (isLoggedIn ? '重新认证 / 切换账号登录' : '登录教务在线') }}
            </UiButton>
          </div>
        </form>
      </div>
    </UiCard>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue';
import UiCard from '@/components/ui/UiCard.vue';
import UiInput from '@/components/ui/UiInput.vue';
import UiButton from '@/components/ui/UiButton.vue';
import Icon from '@/components/icons/Icon.vue';
import logoUrl from '@/assets/HRBUST.png';
import { useSession } from '@/composables/useSession.js';
import { useToast } from '@/composables/useToast.js';
import { academicApi } from '@/services/academic/api.js';

const {
  isLoggedIn,
  userProfile,
  studentNumber,
  isLoggingIn,
  login,
  navigateTo
} = useSession();

const { showToast } = useToast();

const showPassword = ref(false);
const rememberMe = ref(true);
const captchaImgUrl = ref('');
const captchaLoading = ref(false);
const captchaError = ref(false);
const errorMessage = ref('');

const form = reactive({
  username: studentNumber.value || '',
  password: '',
  captcha: ''
});

const canSubmit = computed(() => {
  return form.username.trim() && form.password.trim() && form.captcha.trim().length === 4;
});

function refreshCaptcha() {
  captchaLoading.value = true;
  captchaError.value = false;
  form.captcha = '';
  captchaImgUrl.value = academicApi.getCaptchaUrl();
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
    showToast({
      title: '登录成功',
      message: `欢迎回来，${userProfile.realName || form.username} 同学！`,
      type: 'success'
    });
    // 登录成功后直接跳回概览页面
    navigateTo('dashboard');
  } else {
    errorMessage.value = res.message || '登录失败，请检查账号、密码及验证码';
    refreshCaptcha();
  }
}

onMounted(() => {
  refreshCaptcha();
  if (studentNumber.value && !form.username) {
    form.username = studentNumber.value;
  }
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
