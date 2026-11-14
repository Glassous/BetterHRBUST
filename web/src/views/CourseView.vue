<template>
  <div class="space-y-6">
    <!-- 未登录提示 -->
    <UiCard v-if="showLoginPrompt" class="py-12 text-center max-w-lg mx-auto">
      <div class="w-13 h-13 rounded-2xl bg-[var(--accent-soft)] text-[var(--accent)] flex items-center justify-center mx-auto mb-3.5">
        <Icon name="course" customClass="w-6 h-6" />
      </div>
      <h3 class="text-base font-semibold text-[var(--ink-primary)] mb-1">未登录教务系统</h3>
      <p class="text-[13px] text-[var(--ink-secondary)] mb-5">请登录哈理工教务在线以查询自己本学期修读的课程</p>
      <UiButton variant="primary" size="sm" @click="openLoginModal">立即登录</UiButton>
    </UiCard>

    <template v-else>
      <!-- 课程检索与筛选栏 -->
      <UiCard bodyClass="p-4 sm:p-5">
        <div class="flex flex-wrap items-center justify-between gap-3.5">
          <div class="w-full sm:w-80">
            <UiInput
              v-model="searchKey"
              placeholder="检索课程名称、课程编号或任课教师..."
              size="sm"
              clearable
            >
              <template #prefix>
                <Icon name="search" customClass="w-4 h-4" />
              </template>
            </UiInput>
          </div>

          <UiTabs
            :items="[
              { label: '全部已修/在修', value: 'all' },
              { label: '本学期在修', value: 'current' },
              { label: '必修课', value: '必修' },
              { label: '选修课', value: '选' }
            ]"
            v-model="categoryFilter"
          />
        </div>
      </UiCard>

      <div v-if="syncing && courses.length === 0" class="py-14 text-center text-[13px] text-[var(--ink-muted)]">
        <Icon name="refresh" customClass="w-5 h-5 animate-spin mx-auto mb-2.5 text-[var(--accent)]" />
        正在拉取课程名录...
      </div>

      <div v-else-if="filteredCourses.length === 0" class="py-12 rounded-[var(--radius-card)] border border-[var(--border-hairline)] bg-[var(--bg-surface)] text-center text-[14px] text-[var(--ink-muted)]">
        未找到匹配的课程
      </div>

      <!-- 课程网格 -->
      <div v-else class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
        <UiCard
          v-for="c in filteredCourses"
          :key="c.id + c.name"
          hoverable
        >
          <div class="space-y-3.5">
            <div class="flex items-center justify-between gap-2">
              <span class="text-[13px] font-mono text-[var(--ink-muted)] num-tabular">{{ c.id }}</span>
              <div class="flex items-center gap-1.5">
                <UiBadge v-if="c.isCurrent" size="sm" variant="accent">本学期</UiBadge>
                <UiBadge size="sm" :variant="c.property === '必修' ? 'default' : 'outline'">{{ c.property }}</UiBadge>
              </div>
            </div>

            <div>
              <h4 class="text-[15px] font-semibold text-[var(--ink-primary)] leading-snug">{{ c.name }}</h4>
              <div class="text-[13px] text-[var(--ink-secondary)] mt-1.5 num-tabular">
                {{ c.group || '课程' }} · {{ c.credits }} 学分<span v-if="c.hours"> ({{ c.hours }} 学时)</span>
              </div>
            </div>

            <div class="pt-3.5 border-t border-[var(--border-hairline)] flex items-center justify-between text-[13px]">
              <span class="text-[var(--ink-muted)]">
                任课教师：<span class="font-medium text-[var(--ink-primary)]">{{ c.teacher || '未提供教师信息' }}</span>
              </span>
            </div>
          </div>
        </UiCard>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue';
import UiCard from '@/components/ui/UiCard.vue';
import UiInput from '@/components/ui/UiInput.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiBadge from '@/components/ui/UiBadge.vue';
import UiTabs from '@/components/ui/UiTabs.vue';
import Icon from '@/components/icons/Icon.vue';
import { useSession } from '@/composables/useSession.js';
import { useAcademicData } from '@/composables/useAcademicData.js';

const { isLoggedIn, isSessionExpired, openLoginModal } = useSession();
const { currentCourses, scores, syncing } = useAcademicData();

const showLoginPrompt = computed(() => !isLoggedIn.value && !isSessionExpired.value);

const searchKey = ref('');
const categoryFilter = ref('all');

const courses = computed(() => {
  const list = [];
  const seen = new Set();

  (currentCourses.value || []).forEach(c => {
    if (!c.courseId) return;
    seen.add(c.courseId);
    list.push({
      id: c.courseId,
      name: c.courseName,
      property: c.property || '必修',
      group: '本学期排课',
      credits: c.credit || 0,
      hours: 0,
      teacher: c.teacher || '',
      isCurrent: true
    });
  });

  (scores.value || []).forEach(s => {
    if (s.courseId && !seen.has(s.courseId)) {
      seen.add(s.courseId);
      list.push({
        id: s.courseId,
        name: s.courseName,
        property: s.property || '必修',
        group: s.courseGroup || '',
        credits: s.credit || 0,
        hours: s.hours || 0,
        teacher: s.teacher || '',
        isCurrent: false
      });
    }
  });

  return list;
});

const filteredCourses = computed(() => {
  return courses.value.filter(c => {
    if (categoryFilter.value === 'current' && !c.isCurrent) return false;
    if (categoryFilter.value === '必修' && !c.property.includes('必修')) return false;
    if (categoryFilter.value === '选' && !c.property.includes('选')) return false;

    if (searchKey.value) {
      const q = searchKey.value.toLowerCase();
      if (!c.name.toLowerCase().includes(q) && !c.id.toLowerCase().includes(q) && !c.teacher.toLowerCase().includes(q)) {
        return false;
      }
    }
    return true;
  });
});
</script>
