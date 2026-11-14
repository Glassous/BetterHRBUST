<template>
  <div class="space-y-6 max-w-4xl">
    <!-- Academic Affairs Online Connection Status -->
    <UiCard title="教务在线连接状态">
      <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-3.5">
        <div class="flex items-center gap-3">
          <span
            class="w-2.5 h-2.5 rounded-full shrink-0"
            :class="isLoggedIn ? 'bg-[var(--sem-success)]' : 'bg-[var(--sem-warning)]'"
          />
          <span class="text-[15px] font-semibold text-[var(--ink-primary)]">
            {{ isLoggedIn ? '教务在线已连接并已登录' : '教务在线网关已配置（未登录）' }}
          </span>
        </div>
        <UiButton variant="outline" size="sm" @click="openLoginModal">
          {{ isLoggedIn ? '重新登录' : '登录教务在线' }}
        </UiButton>
      </div>
    </UiCard>

    <!-- Theme Settings Card -->
    <UiCard title="外观与个性化设置">
      <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <div class="text-[15px] font-semibold text-[var(--ink-primary)]">深色模式 / 浅色模式</div>
          <div class="text-[13px] text-[var(--ink-muted)] mt-1">冷静矿石与教务索引配色系统（自动适配浅色与深色界面）</div>
        </div>
        <UiTabs
          :items="[
            { label: '跟随系统', value: 'system' },
            { label: '浅色', value: 'light' },
            { label: '深色', value: 'dark' }
          ]"
          :modelValue="themeMode"
          @update:modelValue="setTheme($event)"
        />
      </div>
    </UiCard>

    <!-- System Architecture & Probe Summary Card -->
    <UiCard title="关于 BetterHRBUST">
      <div class="text-[13px] text-[var(--ink-secondary)] space-y-3.5 leading-relaxed">
        <p>
          <strong class="text-[var(--ink-primary)]">BetterHRBUST</strong> 是针对哈尔滨理工大学教务在线（清华教育在线 / 优慕课 URP 架构）的现代化套壳客户端。
        </p>
        <p>
          本项目为开源软件，遵循 <a
            href="https://github.com/Glassous/BetterHRBUST/blob/main/LICENSE"
            target="_blank"
            rel="noopener noreferrer"
            class="font-semibold text-[var(--accent)] underline decoration-[var(--accent-border)] underline-offset-2 hover:decoration-[var(--accent)] transition-colors"
          >MIT License</a> 协议发布，任何人都可以自由获取、修改与分发源码。欢迎前往仓库提交 Issue 或 Pull Request 参与共建，也欢迎点亮 Star 支持项目发展。
        </p>
        <div class="pt-1.5">
          <a
            href="https://github.com/Glassous/BetterHRBUST"
            target="_blank"
            rel="noopener noreferrer"
            class="inline-flex items-center gap-2.5 px-4 py-2.5 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--bg-subtle)] hover:bg-[var(--bg-hover)] hover:border-[var(--border-strong)] hover:-translate-y-0.5 active:scale-[0.98] transition-all duration-150 group"
          >
            <Icon name="github" custom-class="w-4 h-4 text-[var(--ink-primary)]" />
            <span class="text-[13px] font-medium text-[var(--ink-primary)]">GitHub 开源仓库</span>
            <span class="font-mono text-[13px] text-[var(--ink-muted)] group-hover:text-[var(--ink-secondary)] transition-colors">Glassous/BetterHRBUST</span>
            <Icon name="external-link" custom-class="w-3.5 h-3.5 text-[var(--ink-muted)] group-hover:text-[var(--ink-primary)] transition-colors" />
          </a>
        </div>
      </div>
    </UiCard>

    <!-- 命令行模式（桌面端 Tauri 专属：Web / 油猴环境内部自行隐藏） -->
    <CliCard />

    <!-- 版本更新（桌面端 Tauri 专属：Web / 油猴环境内部自行隐藏） -->
    <UpdateCard />
  </div>
</template>

<script setup>
import UiCard from '@/components/ui/UiCard.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiTabs from '@/components/ui/UiTabs.vue';
import Icon from '@/components/icons/Icon.vue';
import CliCard from '@/components/settings/CliCard.vue';
import UpdateCard from '@/components/settings/UpdateCard.vue';
import { useTheme } from '@/composables/useTheme.js';
import { useSession } from '@/composables/useSession.js';

const { themeMode, setTheme } = useTheme();
const { isLoggedIn, openLoginModal } = useSession();
</script>