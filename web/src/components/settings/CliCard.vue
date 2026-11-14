<template>
  <!-- 桌面端（Tauri）专属：命令行只随桌面安装版分发，网页 / 油猴不渲染 -->
  <UiCard
    v-if="supported"
    title="命令行模式"
    subtitle="在终端查询本地教务缓存 · 只读、不联网、不需要验证码"
  >
    <template #title-prefix>
      <div
        class="w-9 h-9 rounded-xl bg-[var(--accent-soft)] text-[var(--accent)] border border-[var(--accent-border)] flex items-center justify-center shrink-0"
      >
        <Icon name="terminal" custom-class="w-[18px] h-[18px]" />
      </div>
    </template>

    <template #header-action>
      <UiButton variant="outline" size="sm" :loading="loading" @click="reload">
        <template #prefix>
          <Icon name="refresh" custom-class="w-3.5 h-3.5" />
        </template>
        刷新状态
      </UiButton>
    </template>

    <div class="space-y-5">
      <!-- 实时状态：命令行是否可用、数据源、登录态 -->
      <div class="grid grid-cols-1 sm:grid-cols-3 gap-3">
        <div
          v-for="item in statusItems"
          :key="item.label"
          class="flex items-start gap-2.5 rounded-[var(--radius-panel)] border border-[var(--border-hairline)] bg-[var(--bg-subtle)]/55 px-3.5 py-3"
        >
          <span class="mt-[7px] w-2 h-2 rounded-full shrink-0" :class="item.dotClass" />
          <div class="min-w-0">
            <div class="text-[13px] font-semibold text-[var(--ink-primary)]">{{ item.label }}</div>
            <div class="text-[12px] text-[var(--ink-muted)] mt-0.5 leading-relaxed break-all">
              {{ item.value }}
            </div>
          </div>
        </div>
      </div>

      <!-- 未就绪引导：与终端里的提示文案保持同一口径 -->
      <div
        v-if="notice"
        class="flex items-start gap-2.5 rounded-[var(--radius-panel)] border border-[var(--sem-warning-border)] bg-[var(--sem-warning-soft)] px-3.5 py-3"
      >
        <Icon name="alert-triangle" custom-class="w-4 h-4 shrink-0 text-[var(--sem-warning)] mt-0.5" />
        <div class="text-[13px] leading-relaxed text-[var(--ink-secondary)] min-w-0">
          <p class="font-semibold text-[var(--ink-primary)]">{{ notice.title }}</p>
          <p class="mt-0.5">{{ notice.text }}</p>
        </div>
      </div>

      <!-- 命令速查 -->
      <div>
        <div class="flex items-baseline justify-between gap-3">
          <h4 class="text-[13px] font-semibold text-[var(--ink-primary)]">命令速查</h4>
          <span class="text-[12px] text-[var(--ink-muted)]">任意命令追加 --json 可获得机器可读输出</span>
        </div>
        <div class="mt-2 rounded-[var(--radius-panel)] border border-[var(--border-hairline)] overflow-hidden">
          <div
            v-for="item in commands"
            :key="item.command"
            class="flex flex-col sm:flex-row sm:items-center gap-0.5 sm:gap-4 px-4 py-2.5 border-b border-[var(--border-hairline)] last:border-b-0 hover:bg-[var(--bg-hover)] transition-colors duration-150"
          >
            <code class="font-mono text-[12.5px] text-[var(--ink-primary)] shrink-0 sm:w-[300px]">
              {{ item.command }}
            </code>
            <span class="text-[13px] text-[var(--ink-secondary)] leading-relaxed">{{ item.description }}</span>
          </div>
        </div>
      </div>

      <!-- 可复制的完整命令（含真实安装路径） -->
      <div
        class="flex flex-col sm:flex-row sm:items-center gap-2.5 rounded-[var(--radius-panel)] border border-[var(--border-hairline)] bg-[var(--bg-subtle)]/60 px-3.5 py-3"
      >
        <code class="flex-1 min-w-0 font-mono text-[12.5px] text-[var(--ink-secondary)] break-all">
          {{ exampleCommand }}
        </code>
        <UiButton variant="outline" size="sm" customClass="shrink-0" @click="copy(exampleCommand, 'command')">
          <template #prefix>
            <Icon :name="copied === 'command' ? 'check' : 'download'" custom-class="w-3.5 h-3.5" :class="copied === 'command' ? 'text-[var(--sem-success)]' : ''" />
          </template>
          {{ copied === 'command' ? '已复制' : '复制命令' }}
        </UiButton>
      </div>

      <!-- SKILL：给用户自己的 AI 工具使用 -->
      <div>
        <div class="flex flex-wrap items-center justify-between gap-2">
          <div class="min-w-0">
            <h4 class="text-[13px] font-semibold text-[var(--ink-primary)]">SKILL · 交给 AI 工具使用</h4>
            <p class="text-[12px] text-[var(--ink-muted)] mt-0.5 leading-relaxed">
              内含命令、退出码与只读约束；复制后放进 AI 工具的技能目录，或执行
              <code class="font-mono">BetterHRBUSTCLI skill --save &lt;目录&gt;</code> 自动写入。
            </p>
          </div>
          <UiButton
            variant="outline"
            size="sm"
            customClass="shrink-0"
            :disabled="!skill"
            @click="copy(skill, 'skill')"
          >
            <template #prefix>
              <Icon :name="copied === 'skill' ? 'check' : 'file-text'" custom-class="w-3.5 h-3.5" :class="copied === 'skill' ? 'text-[var(--sem-success)]' : ''" />
            </template>
            {{ copied === 'skill' ? '已复制' : '复制 SKILL' }}
          </UiButton>
        </div>

        <pre
          v-if="skill"
          class="mt-2.5 max-h-[220px] overflow-auto rounded-[var(--radius-panel)] border border-[var(--border-hairline)] bg-[var(--bg-subtle)]/70 px-3.5 py-3 font-mono text-[11.5px] leading-relaxed text-[var(--ink-secondary)] whitespace-pre-wrap break-words"
        >{{ skill }}</pre>
        <p v-else class="mt-2.5 text-[12px] text-[var(--ink-muted)]">
          当前环境读不到 SKILL 文本（开发模式或非安装版），可在终端执行
          <code class="font-mono">BetterHRBUSTCLI skill</code> 直接输出。
        </p>
      </div>
    </div>

    <template #footer>
      <p class="text-[12px] text-[var(--ink-muted)] leading-relaxed">
        安装时已把安装目录写入用户 PATH，<span class="text-[var(--ink-secondary)]">已打开的终端需要重开后才能识别命令</span>；
        便携版可直接运行所在目录下的
        <code class="font-mono">BetterHRBUSTCLI.exe</code>。
        命令行不联网、不写数据，登录与数据刷新仍需在图形界面完成。
      </p>
    </template>
  </UiCard>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import UiCard from '@/components/ui/UiCard.vue';
import UiButton from '@/components/ui/UiButton.vue';
import Icon from '@/components/icons/Icon.vue';
import { isDesktopApp, isUserscript } from '@/utils/platform.js';
import { fetchCliInfo, fetchCliSkill } from '@/services/cliDocs.js';

/**
 * 仅桌面端（含 tauri dev）渲染：
 * 网页与油猴既没有本地服务（/__app/cli），也没有随安装包分发的 BetterHRBUSTCLI.exe，
 * 显示这张卡片只会给出无法照做的指引，因此一律隐藏。
 */
const supported = isDesktopApp && !isUserscript;

const info = ref(null);
const skill = ref('');
const loading = ref(false);
const copied = ref('');

const commands = [
  { command: 'BetterHRBUSTCLI status', description: '登录状态、本地数据条数、上次同步时间' },
  { command: 'BetterHRBUSTCLI summary [--date 日期]', description: '概览：身份、当日课程、近期考试、学业指标' },
  { command: 'BetterHRBUSTCLI timetable', description: '课程表（--week 周次 / --today 今日 / --date 指定日期 / --course 关键字）' },
  { command: 'BetterHRBUSTCLI scores', description: '成绩与 GPA（--course 课程 / --property 属性 / --failed 挂科 / --merged 合并清单）' },
  { command: 'BetterHRBUSTCLI exams [--days N]', description: '考试日程（默认近期，--all 全部，--days 调整窗口）' },
  { command: 'BetterHRBUSTCLI plan [--group 关键字]', description: '培养方案各课组学分完成度' },
  { command: 'BetterHRBUSTCLI notices [--query 关键字]', description: '教学公告（--week 指定周次、--limit 限制条数）' },
  { command: 'BetterHRBUSTCLI profile', description: '学籍档案（证件号、手机号默认打码）' },
  { command: 'BetterHRBUSTCLI courses [--query 关键字]', description: '本学期课程名录' },
  { command: 'BetterHRBUSTCLI check [--strict]', description: '错误检查：数据完整性、会话、同步失败项与数据新鲜度' },
  { command: 'BetterHRBUSTCLI doctor', description: '数据文件、路径与缓存键诊断' },
  { command: 'BetterHRBUSTCLI skill --save .', description: '把 SKILL 写入当前目录，供 AI 工具使用' }
];

async function reload() {
  if (!supported) return; // 非桌面端不发起请求（卡片本身也不渲染）
  loading.value = true;
  try {
    const [infoResult, skillResult] = await Promise.all([fetchCliInfo(), fetchCliSkill()]);
    info.value = infoResult;
    skill.value = skillResult || '';
  } finally {
    loading.value = false;
  }
}

onMounted(reload);

const statusItems = computed(() => {
  const value = info.value;
  if (!value) {
    return [
      { label: '命令行程序', value: '状态不可读', dotClass: 'bg-[var(--sem-warning)]' },
      { label: '数据源', value: '状态不可读', dotClass: 'bg-[var(--sem-warning)]' },
      { label: '登录状态', value: '状态不可读', dotClass: 'bg-[var(--sem-warning)]' }
    ];
  }
  const counts = value.counts || {};
  return [
    {
      label: '命令行程序',
      value: value.cliInstalled ? '已安装' : '未找到 BetterHRBUSTCLI.exe',
      dotClass: value.cliInstalled ? 'bg-[var(--sem-success)]' : 'bg-[var(--sem-danger)]'
    },
    {
      label: '数据源',
      value: value.hasData
        ? `成绩 ${counts.scores ?? 0} 条 · 考试 ${counts.exams ?? 0} 条 · 课表 ${counts.timetableCells ?? 0} 格`
        : '本地暂无缓存',
      dotClass: value.hasData ? 'bg-[var(--sem-success)]' : 'bg-[var(--sem-warning)]'
    },
    {
      label: '登录状态',
      value: value.loggedIn
        ? `已登录${value.account ? ` · ${value.account}` : ''}`
        : value.hasData
          ? '离线缓存（仍可查询）'
          : '未登录',
      dotClass: value.loggedIn ? 'bg-[var(--sem-success)]' : 'bg-[var(--sem-warning)]'
    }
  ];
});

const notice = computed(() => {
  const value = info.value;
  if (!value) {
    return {
      title: '未能读取命令行状态',
      text: '仅安装版桌面端提供本地服务（此环境为开发模式或非安装版）。下方命令参考仍然有效。'
    };
  }
  if (!value.cliInstalled) {
    return {
      title: '未检测到命令行程序',
      text: '请重新安装最新版桌面端安装包：它会一并安装 BetterHRBUSTCLI.exe 并把安装目录写入用户 PATH。'
    };
  }
  if (!value.hasData) {
    return {
      title: '本地暂无教务数据',
      text: '登录需要学号、密码与图片验证码，终端无法完成。请先在此窗口登录一次（登录后会自动全量同步），命令行即可查询。'
    };
  }
  if (value.lastSyncError) {
    return {
      title: '上次同步存在失败项',
      text: `${value.lastSyncError}（在终端执行 BetterHRBUSTCLI check 可查看完整错误检查明细）`
    };
  }
  return null;
});

/** 展示真实安装路径，用户可直接复制到终端执行 */
const exampleCommand = computed(() => {
  const path = info.value?.cliPath;
  if (!path) return 'BetterHRBUSTCLI status';
  return `"${path}" status`;
});

async function copy(text, tag) {
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    // WebView 未授权剪贴板时回退到选区复制，避免弹错误打断操作
    const area = document.createElement('textarea');
    area.value = text;
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    try {
      document.execCommand('copy');
    } catch {
      // 复制失败时保留原文供手动选择
    }
    area.remove();
  }
  copied.value = tag;
  setTimeout(() => {
    if (copied.value === tag) copied.value = '';
  }, 1800);
}
</script>
