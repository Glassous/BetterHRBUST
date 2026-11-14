<template>
  <div class="space-y-6">
    <!-- 操作控制头 -->
    <UiCard title="教学评价助手" subtitle="按你设置的选项和评语处理所选课程，逐门核对学校返回的完成状态">
      <div class="flex flex-wrap items-center gap-2.5">
        <UiButton size="sm" variant="outline" :loading="loading" :disabled="running" @click="loadTasks">
          刷新评价列表
        </UiButton>
        <UiButton size="sm" variant="secondary" :disabled="!selected.length || loading || running" @click="loadTemplate">
          读取问卷并配置
        </UiButton>
        <UiButton size="sm" variant="primary" :disabled="!template || !selected.length || running || loading" @click="review">
          检查配置并开始批量提交
        </UiButton>
        <UiButton v-if="running" size="sm" variant="danger" @click="stopRequested = true">
          处理完当前课程后停止
        </UiButton>
      </div>

      <div
        v-if="message"
        role="status"
        class="mt-4 px-4 py-3 rounded-[var(--radius-control)] border border-[var(--accent-border)] bg-[var(--accent-soft)] text-[13px] text-[var(--accent)] font-medium leading-relaxed"
      >
        {{ message }}
      </div>
      <div
        v-if="error"
        role="alert"
        class="mt-3.5 px-4 py-3 rounded-[var(--radius-control)] border border-[var(--sem-danger-border)] bg-[var(--sem-danger-soft)] text-[13px] text-[var(--sem-danger)] font-medium leading-relaxed"
      >
        {{ error }}
      </div>
    </UiCard>

    <!-- 待评课程队列 -->
    <UiCard title="评价课程" :subtitle="tasks.length ? `共 ${tasks.length} 门，已选 ${selected.length} 门待提交` : ''" bodyClass="p-0">
      <p v-if="!tasks.length && !loading" class="px-6 py-10 text-center text-[13px] text-[var(--ink-muted)]">
        原站尚未返回评价记录，请确认处于评价开放期。
      </p>

      <div v-else class="divide-y divide-[var(--border-hairline)]">
        <label
          v-for="task in tasks"
          :key="task.key"
          :class="[
            'px-5 sm:px-6 py-4 flex items-center gap-3.5 text-[14px] transition-colors',
            running || !task.pending || !task.url
              ? 'opacity-70 cursor-not-allowed'
              : 'hover:bg-[var(--bg-subtle)]/55 cursor-pointer'
          ]"
        >
          <input
            v-model="chosen"
            type="checkbox"
            :value="task.key"
            :disabled="running || !task.pending || !task.url"
            :aria-label="'选择 ' + task.course"
            class="w-4 h-4 rounded border-[var(--border-strong)] accent-[var(--accent)] cursor-pointer disabled:cursor-not-allowed shrink-0"
          />
          <span class="flex-1 font-medium text-[var(--ink-primary)] min-w-0">
            {{ task.course }} · {{ task.teacher }}
          </span>
          <UiBadge
            size="sm"
            :variant="task.pending ? 'warning' : 'success'"
          >
            {{ progress[task.key] || task.status }}
          </UiBadge>
        </label>
      </div>
    </UiCard>

    <!-- 统一评价配置表单（保留原生 select 以保障无障碍与自动测试兼容性） -->
    <UiCard v-if="template" title="统一评价配置" subtitle="以下为原问卷中的项目，配置用于本次所选课程。问卷结构不同的课程会停止处理">
      <div class="space-y-5">
        <label
          v-for="(group, index) in template.groups"
          :key="group.name"
          class="block space-y-2"
        >
          <span class="block text-[14px] font-semibold text-[var(--ink-primary)]">{{ group.question }}</span>
          <select
            v-model="ratings[index]"
            :disabled="running"
            :aria-label="'评价项目 ' + (index + 1)"
            class="w-full min-h-[42px] px-4 py-2.5 rounded-[var(--radius-control)] border border-[var(--border-hairline)] hover:border-[var(--border-strong)] bg-[var(--bg-subtle)] focus:bg-[var(--bg-surface)] focus:border-[var(--accent)] focus:outline-none focus:ring-2 focus:ring-[var(--accent-soft)] text-[14px] text-[var(--ink-primary)] disabled:opacity-50 transition-all duration-150"
          >
            <option value="">请选择</option>
            <option
              v-for="(option, optionIndex) in group.options"
              :key="optionIndex"
              :value="String(optionIndex)"
              class="bg-[var(--bg-surface)] text-[var(--ink-primary)]"
            >
              {{ option.label }}
            </option>
          </select>
        </label>

        <label
          v-for="(comment, index) in template.comments"
          :key="comment.name"
          class="block space-y-2"
        >
          <span class="block text-[14px] font-semibold text-[var(--ink-primary)]">{{ comment.question }}</span>
          <UiTextarea
            v-model="comments[index]"
            :disabled="running"
            :aria-label="'文字评价 ' + (index + 1)"
            :rows="3"
          />
        </label>
      </div>
    </UiCard>

    <!-- 批量确认弹窗 -->
    <UiModal v-model="confirming" title="确认批量教学评价">
      <div class="space-y-5 text-[14px]">
        <p class="text-[var(--ink-secondary)] leading-relaxed">
          将向学校提交 <strong class="font-semibold text-[var(--ink-primary)] num-tabular">{{ selected.length }}</strong> 门课程的教学评价，使用下面的配置：
        </p>

        <ul class="divide-y divide-[var(--border-hairline)] border-y border-[var(--border-hairline)]">
          <li
            v-for="(group, index) in template?.groups || []"
            :key="group.name"
            class="py-3 flex items-center justify-between gap-3 text-[13px] sm:text-[14px]"
          >
            <span class="text-[var(--ink-secondary)]">{{ group.question }}</span>
            <span class="font-medium text-[var(--ink-primary)] shrink-0">{{ group.options[Number(ratings[index])]?.label }}</span>
          </li>
        </ul>

        <div
          v-for="(comment, index) in template?.comments || []"
          :key="comment.name"
          class="p-4 rounded-[var(--radius-panel)] bg-[var(--bg-subtle)] border border-[var(--border-hairline)] space-y-1.5"
        >
          <p class="text-[13px] font-medium text-[var(--ink-secondary)]">{{ comment.question }}</p>
          <p class="text-[14px] whitespace-pre-wrap text-[var(--ink-primary)] leading-relaxed">{{ comments[index] }}</p>
        </div>

        <div class="flex flex-wrap gap-3 pt-2">
          <UiButton variant="primary" :disabled="running || loading" @click="startBatch">确认使用以上配置提交</UiButton>
          <UiButton variant="outline" @click="confirming = false">返回修改</UiButton>
        </div>
      </div>
    </UiModal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import UiCard from '@/components/ui/UiCard.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiBadge from '@/components/ui/UiBadge.vue';
import UiModal from '@/components/ui/UiModal.vue';
import UiTextarea from '@/components/ui/UiTextarea.vue';
import { useSession } from '@/composables/useSession.js';
import { getEvaluationTasks, getEvaluationForm, evaluationBody, runEvaluationBatch } from '@/services/academic/evaluation.js';

const { isLoggedIn } = useSession();
const tasks = ref([]);
const chosen = ref([]);
const template = ref(null);
const ratings = ref([]);
const comments = ref([]);
const loading = ref(false);
const running = ref(false);
const confirming = ref(false);
const stopRequested = ref(false);
const message = ref('');
const error = ref('');
const progress = ref({});

const selected = computed(() => tasks.value.filter(task => task.pending && task.url && chosen.value.includes(task.key)));

async function loadTasks() {
  if (!isLoggedIn.value) { error.value = '请先登录教务系统'; return; }
  loading.value = true; error.value = '';
  try { tasks.value = await getEvaluationTasks(); chosen.value = tasks.value.filter(task => task.pending && task.url).map(task => task.key); }
  catch (err) { error.value = err.message; }
  finally { loading.value = false; if (!running.value) progress.value = {}; }
}

async function loadTemplate() {
  if (!selected.value.length || loading.value || running.value) return;
  loading.value = true; error.value = '';
  try {
    template.value = await getEvaluationForm(selected.value[0]);
    ratings.value = template.value.groups.map(() => ''); comments.value = template.value.comments.map(() => '');
  } catch (err) { error.value = err.message; }
  finally { loading.value = false; }
}

function review() {
  if (loading.value || running.value) return;
  try { evaluationBody(template.value, { ratings: ratings.value, comments: comments.value }); error.value = ''; confirming.value = true; }
  catch (err) { error.value = err.message; }
}

async function startBatch() {
  if (running.value || loading.value) return;
  confirming.value = false; running.value = true; stopRequested.value = false; error.value = '';
  const batch = [...selected.value];
  const settings = { ratings: [...ratings.value], comments: [...comments.value] };
  try {
    const count = await runEvaluationBatch(batch, template.value, settings, {
      stopped: () => stopRequested.value,
      progress: (task, status) => { progress.value[task.key] = status; message.value = task.course + '：' + status; }
    });
    message.value = `已核对完成 ${count} 门课程。${stopRequested.value ? '其余课程已停止。' : '可返回成绩页重新同步。'}`;
  } catch (err) { error.value = err.message; message.value = '批量任务已停止，请刷新评价列表核对状态。'; }
  finally {
    running.value = false;
    const failure = error.value;
    await loadTasks();
    if (failure) error.value = failure;
  }
}

onMounted(loadTasks);
onBeforeUnmount(() => { stopRequested.value = true; });
</script>
