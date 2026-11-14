<template>
  <div class="space-y-6">
    <!-- Unauthenticated State -->
    <UiCard v-if="!isLoggedIn" class="py-12 text-center max-w-lg mx-auto">
      <div class="w-13 h-13 rounded-2xl bg-[var(--bg-subtle)] border border-[var(--border-hairline)] flex items-center justify-center mx-auto mb-3.5 text-[var(--ink-secondary)]">
        <Icon name="profile" customClass="w-6 h-6" />
      </div>
      <h3 class="text-base font-semibold text-[var(--ink-primary)] mb-1">未登录教务系统</h3>
      <p class="text-[13px] text-[var(--ink-muted)] mb-5">请登录哈理工教务在线以查询您的真实学籍档案与异动记录</p>
      <UiButton variant="primary" size="sm" @click="openLoginModal">立即登录</UiButton>
    </UiCard>

    <template v-else>
      <!-- Student Campus Card Style Profile -->
      <UiCard>
        <div class="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-5">
          <div class="flex items-center gap-4 sm:gap-5 min-w-0">
            <!-- Avatar / Photo -->
            <div class="w-16 h-16 sm:w-20 sm:h-20 rounded-2xl bg-[var(--accent-soft)] text-[var(--accent)] border border-[var(--accent-border)] flex items-center justify-center font-semibold text-2xl shrink-0 overflow-hidden">
              <img
                v-if="userProfile.photoUrl"
                :src="userProfile.photoUrl"
                alt="学生照片"
                class="w-full h-full object-cover"
              />
              <span v-else>{{ userProfile.realName ? userProfile.realName.slice(0, 1) : '学' }}</span>
            </div>
            <div class="min-w-0">
              <div class="flex items-center gap-2.5 flex-wrap">
                <h2 class="text-2xl font-semibold text-[var(--ink-primary)] tracking-tight">{{ userProfile.realName || '哈理工同学' }}</h2>
                <UiBadge variant="success" dot size="sm">{{ userProfile.status || '在籍' }}</UiBadge>
              </div>
              <div class="text-[13px] text-[var(--ink-muted)] mt-1.5 flex flex-wrap gap-x-4 gap-y-1">
                <span>学号 <span class="font-mono num-tabular text-[var(--ink-primary)] font-medium">{{ userProfile.studentNumber || studentNumber }}</span></span>
                <span v-if="userProfile.internalId">教务 ID <span class="font-mono num-tabular text-[var(--ink-primary)] font-medium">{{ userProfile.internalId }}</span></span>
              </div>
              <div class="text-[13px] text-[var(--ink-secondary)] mt-1 truncate">
                {{ userProfile.college || '未知学院' }} · {{ userProfile.major || '未知专业' }} <span v-if="userProfile.className">({{ userProfile.className }})</span>
              </div>
            </div>
          </div>

          <div class="flex items-center gap-2.5 shrink-0 flex-wrap">
            <!-- Sync Profile Button -->
            <UiButton
              variant="outline"
              size="sm"
              :loading="loading"
              @click="refreshProfile"
            >
              <template #prefix>
                <Icon name="refresh" customClass="w-3.5 h-3.5" />
              </template>
              同步档案
            </UiButton>

            <!-- Privacy Mask Switch Button -->
            <UiButton
              variant="outline"
              size="sm"
              @click="isMasked = !isMasked"
            >
              <template #prefix>
                <Icon :name="isMasked ? 'eye' : 'eye-off'" customClass="w-3.5 h-3.5" />
              </template>
              {{ isMasked ? '显示完整信息' : '脱敏隐私信息' }}
            </UiButton>
          </div>
        </div>
      </UiCard>

      <!-- Details Grid -->
      <div class="grid grid-cols-1 md:grid-cols-2 gap-5 sm:gap-6">
        <!-- Academic Status Info -->
        <UiCard title="学籍详细档案">
          <dl class="divide-y divide-[var(--border-hairline)] text-[13px] -my-1">
            <div class="flex items-center justify-between gap-4 py-3">
              <dt class="text-[var(--ink-muted)]">培养层次 / 学生类别</dt>
              <dd class="font-medium text-[var(--ink-primary)] text-right">{{ userProfile.studentType || '普通本科' }}</dd>
            </div>
            <div class="flex items-center justify-between gap-4 py-3">
              <dt class="text-[var(--ink-muted)]">入学年级</dt>
              <dd class="font-medium num-tabular text-[var(--ink-primary)] text-right">{{ userProfile.grade ? String(userProfile.grade).trim().replace(/(?:\s*级)+$/, '') + '级' : '—' }}</dd>
            </div>
            <div class="flex items-center justify-between gap-4 py-3">
              <dt class="text-[var(--ink-muted)]">专业方向</dt>
              <dd class="font-medium text-[var(--ink-primary)] text-right">{{ userProfile.direction || '默认方向' }}</dd>
            </div>
            <div class="flex items-center justify-between gap-4 py-3">
              <dt class="text-[var(--ink-muted)]">学籍状态</dt>
              <dd class="font-semibold text-[var(--sem-success)] text-right">{{ userProfile.status || '正常' }}</dd>
            </div>
          </dl>
        </UiCard>

        <!-- Sensitive Privacy Fields -->
        <UiCard title="联系方式与敏感凭证 (隐私脱敏保护)">
          <dl class="divide-y divide-[var(--border-hairline)] text-[13px] -my-1">
            <div class="flex items-center justify-between gap-4 py-3">
              <dt class="text-[var(--ink-muted)] shrink-0">证件号码</dt>
              <dd class="font-mono num-tabular text-[var(--ink-primary)] text-right">
                {{ formatIdCard(userProfile.idCard) }}
              </dd>
            </div>
            <div class="flex items-center justify-between gap-4 py-3">
              <dt class="text-[var(--ink-muted)] shrink-0">电子邮箱</dt>
              <dd class="text-[var(--ink-primary)] text-right truncate">
                {{ formatEmail(userProfile.email) }}
              </dd>
            </div>
            <div class="flex items-center justify-between gap-4 py-3">
              <dt class="text-[var(--ink-muted)] shrink-0">联系电话</dt>
              <dd class="font-mono num-tabular text-[var(--ink-primary)] text-right">
                {{ formatPhone(userProfile.phone) }}
              </dd>
            </div>
            <div class="flex items-center justify-between gap-4 py-3">
              <dt class="text-[var(--ink-muted)] shrink-0">通讯地址</dt>
              <dd class="text-[var(--ink-primary)] text-right max-w-xs truncate">
                {{ formatAddress(userProfile.address) }}
              </dd>
            </div>
          </dl>
        </UiCard>
      </div>

      <!-- Status Changes History (学籍异动记录) -->
      <UiCard title="学籍异动记录 (休学/复学/专业分流/降级)">
        <div v-if="!userProfile.changes || userProfile.changes.length === 0" class="text-center py-8 text-[13px] text-[var(--ink-muted)]">
          教务系统未记载异动记录，学籍正常
        </div>
        <div v-else class="space-y-4">
          <div
            v-for="(ch, idx) in userProfile.changes"
            :key="idx"
            class="flex items-start gap-3.5 text-[13px]"
          >
            <div class="w-2 h-2 rounded-full bg-[var(--accent)] mt-1.5 shrink-0" />
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2.5 flex-wrap">
                <span class="font-semibold text-[var(--ink-primary)] text-[14px]">{{ ch.type }}</span>
                <span class="text-[var(--ink-muted)] font-mono num-tabular text-[13px]">{{ ch.date }}</span>
              </div>
              <div class="text-[var(--ink-secondary)] mt-0.5 leading-relaxed">
                {{ ch.reason }} <span v-if="ch.remark">({{ ch.remark }})</span>
              </div>
            </div>
          </div>
        </div>
      </UiCard>
    </template>
  </div>
</template>

<script setup>
import { ref } from 'vue';
import UiCard from '@/components/ui/UiCard.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiBadge from '@/components/ui/UiBadge.vue';
import Icon from '@/components/icons/Icon.vue';
import { useSession } from '@/composables/useSession.js';
import { useToast } from '@/composables/useToast.js';
import { academicApi } from '@/services/academic/api.js';

const { isLoggedIn, userProfile, studentNumber, openLoginModal } = useSession();
const { showToast } = useToast();

const isMasked = ref(true);
const loading = ref(false);

function formatIdCard(card) {
  if (!card) return '未登记';
  if (!isMasked.value) return card;
  if (card.length >= 8) {
    return card.slice(0, 6) + '********' + card.slice(-4);
  }
  return '******';
}

function formatEmail(mail) {
  if (!mail) return '未登记';
  if (!isMasked.value) return mail;
  const parts = mail.split('@');
  if (parts.length === 2) {
    return parts[0].slice(0, 2) + '***@' + parts[1];
  }
  return '***@hrbust.edu.cn';
}

function formatPhone(phone) {
  if (!phone) return '未登记';
  if (!isMasked.value) return phone;
  if (phone.length >= 7) {
    return phone.slice(0, 3) + '****' + phone.slice(-4);
  }
  return '138****0000';
}

function formatAddress(addr) {
  if (!addr) return '未登记';
  if (!isMasked.value) return addr;
  return addr.slice(0, 8) + '***';
}

async function refreshProfile() {
  loading.value = true;
  try {
    const info = await academicApi.getPersonalInfo();
    Object.assign(userProfile, info);
    showToast({ title: '档案已同步更新', type: 'success' });
  } catch (err) {
    showToast({ title: '档案拉取失败', message: err.message, type: 'danger' });
  } finally {
    loading.value = false;
  }
}
</script>
