<script setup lang="ts">
// v1.2.0 → v1.3.0 版本更新海报（1200 × 1600，竖屏 3:4）
// 内容全部硬编码：改文案直接改下面的常量或模板即可，不需要任何后台数据。
import androidLogo from '../assets/android-logo.svg'
import androidQr from '../assets/android-qr.png'

// 图片统一放在 public/，运行时按 BASE_URL 取绝对路径，构建后仍能离线打开
const asset = (name: string) => `${import.meta.env.BASE_URL}${name}`
const iconUrl = asset('BetterHRBUST.png')
const desktopShot = asset('classroom-desktop.png')
const androidShot = asset('classroom-android.jpg')

const highlights = [
  { index: '01', title: '真实占用推算', desc: '整楼单日课表解析，连堂 ≥ 2 小节才计入可自习' },
  { index: '02', title: '默认教学楼', desc: '常用教学楼一键设为默认，下次进入自动选中' }
]

const platforms = ['网页', '油猴', 'Windows', 'Android']
</script>

<template>
  <div class="poster-sheet relative h-[1600px] w-[1200px] overflow-hidden bg-[#eef3ff] text-[#0b1526]">
    <!-- 顶部彩色细条 -->
    <div class="absolute inset-x-0 top-0 h-[7px] bg-gradient-to-r from-[#1b64e0] via-[#4d63f5] to-[#8b5cf6]" />

    <!-- 背景：浅色渐变 + 三处光斑 + 网格 -->
    <div class="pointer-events-none absolute inset-0">
      <div class="absolute inset-0 bg-[linear-gradient(158deg,#f9fbff_0%,#eef3ff_46%,#e7eeff_100%)]" />
      <div class="absolute -left-[180px] -top-[250px] h-[780px] w-[780px] bg-[radial-gradient(closest-side,rgba(59,130,246,0.30),rgba(59,130,246,0))]" />
      <div class="absolute -right-[230px] top-[360px] h-[840px] w-[840px] bg-[radial-gradient(closest-side,rgba(139,92,246,0.24),rgba(139,92,246,0))]" />
      <div class="absolute -bottom-[280px] left-[40px] h-[700px] w-[700px] bg-[radial-gradient(closest-side,rgba(45,212,191,0.18),rgba(45,212,191,0))]" />
      <div
        class="absolute inset-0 opacity-60 [background-image:linear-gradient(to_right,rgba(15,23,42,0.05)_1px,transparent_1px),linear-gradient(to_bottom,rgba(15,23,42,0.05)_1px,transparent_1px)] [background-size:52px_52px] [mask-image:radial-gradient(72%_56%_at_50%_24%,#000_28%,transparent_100%)]"
      />
    </div>

    <div class="relative flex h-full flex-col px-[64px] pb-[46px] pt-[50px]">
      <!-- 品牌头 -->
      <div class="flex items-start justify-between">
        <div class="flex items-center gap-3.5">
          <img
            :src="iconUrl"
            alt="BetterHRBUST"
            class="h-[76px] w-[76px] object-contain drop-shadow-[0_12px_20px_rgba(27,100,224,0.28)]"
          />
          <div>
            <div class="text-[24px] font-extrabold leading-none tracking-[-0.02em]">BetterHRBUST</div>
            <div class="mt-2 text-[12.5px] font-medium text-[#5b6b84]">哈理工教务在线 · 第三方增强客户端</div>
          </div>
        </div>
        <div
          class="mt-1 flex items-center gap-2 rounded-full border border-white/80 bg-white/75 px-4 py-[9px] shadow-[0_10px_26px_-16px_rgba(15,23,42,0.55)]"
        >
          <span class="size-[7px] rounded-full bg-[#1b64e0]" />
          <span class="text-[12.5px] font-semibold text-[#33415c]">版本更新</span>
        </div>
      </div>

      <!-- 小标 + 版本跃迁 -->
      <div class="mt-[38px] flex items-center justify-between">
        <span
          class="rounded-full bg-[#1b64e0] px-3.5 py-[7px] text-[11px] font-bold uppercase tracking-[0.2em] text-white"
          >What's New</span
        >
        <div class="flex items-center gap-2.5">
          <span class="rounded-full bg-white/85 px-3.5 py-[7px] text-[14px] font-semibold text-[#8b98ae] ring-1 ring-inset ring-black/[0.07]"
            >v1.2.0</span
          >
          <svg class="size-[18px] text-[#a3b3c9]" viewBox="0 0 20 20" fill="none">
            <path
              d="M4 10h11m0 0-4-4m4 4-4 4"
              stroke="currentColor"
              stroke-width="1.8"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
          <span
            class="rounded-full bg-gradient-to-r from-[#1b64e0] to-[#7b4bf6] px-4 py-[7px] text-[14px] font-bold text-white shadow-[0_14px_26px_-14px_rgba(79,70,229,0.95)]"
            >v1.3.0</span
          >
        </div>
      </div>

      <!-- 主标题 -->
      <h1 class="mt-[24px] text-[94px] font-black leading-[1.03] tracking-[-0.03em]">
        <span class="block">空教室查询</span>
        <span class="relative mt-[4px] block">
          <span class="relative z-10 text-[#1b64e0]">全面升级</span>
          <span
            class="absolute bottom-[-17px] left-[8px] z-0 h-[8px] w-[318px] rounded-full bg-[linear-gradient(90deg,#1b64e0,#7b4bf6)] opacity-85"
          />
        </span>
      </h1>

      <!-- 副标题：手动断行，避免中文在任意位置折行 -->
      <p class="mt-[34px] text-[21px] font-medium leading-[1.62] text-[#42536e]">
        <span class="block">按教学楼拉取整楼单日课表，解析真实占用并推算连续空闲区间</span>
        <span class="mt-[2px] block">
          <span class="font-bold text-[#0b1526]">只保留可自习的连堂</span> —— 找自习教室，不用再猜。
        </span>
      </p>

      <!-- 截图舞台 -->
      <section class="relative mt-[34px] h-[690px] w-full">
        <div class="absolute left-[6px] top-[10px] h-[600px] w-[880px] bg-[radial-gradient(closest-side,rgba(96,165,250,0.34),rgba(96,165,250,0))]" />

        <!-- 桌面端截图：无边框，仅圆角 + 投影 -->
        <div
          class="absolute left-0 top-[8px] w-[900px] overflow-hidden rounded-[18px] bg-[#14161a] shadow-[0_44px_74px_-32px_rgba(15,23,42,0.5),0_10px_24px_-16px_rgba(15,23,42,0.22)]"
        >
          <img :src="desktopShot" alt="空教室查询 · 桌面端占用矩阵" class="block h-[540px] w-full object-cover object-top" />
        </div>

        <!-- 移动端机身：窄边框 -->
        <div
          class="absolute right-0 top-[92px] w-[286px] rounded-[32px] bg-white p-[5px] shadow-[0_46px_76px_-34px_rgba(15,23,42,0.55),0_10px_22px_-12px_rgba(15,23,42,0.16)] ring-1 ring-black/[0.07]"
        >
          <div class="relative overflow-hidden rounded-[27px] bg-[#0f1116]">
            <img :src="androidShot" alt="空教室查询 · Android 端" class="block h-[588px] w-full object-cover object-top" />
          </div>
        </div>
      </section>

      <!-- 更新要点 + 下载二维码 -->
      <section class="mt-auto">
        <div class="flex items-center gap-4">
          <span class="text-[10.5px] font-extrabold uppercase tracking-[0.26em] text-[#95a3b8]">Highlights</span>
          <span class="h-px flex-1 bg-black/[0.09]" />
        </div>
        <div class="mt-[22px] flex items-start justify-between gap-12">
          <!-- 左侧要点：两列，不与二维码共处一个表格 -->
          <div class="grid flex-1 grid-cols-2">
            <div
              v-for="(item, i) in highlights"
              :key="item.index"
              :class="i === 0 ? 'pr-9' : 'border-l border-black/[0.07] px-9'"
            >
              <div class="flex items-baseline gap-2.5">
                <span class="text-[11.5px] font-extrabold tracking-[0.16em] text-[#1b64e0]">{{ item.index }}</span>
                <span class="text-[19.5px] font-bold tracking-[-0.01em]">{{ item.title }}</span>
              </div>
              <p class="mt-[7px] text-[13px] font-medium leading-[1.7] text-[#5b6b84]">{{ item.desc }}</p>
            </div>
          </div>

          <!-- Android 下载二维码：独立靠右，无容器 -->
          <div class="flex shrink-0 flex-col items-center">
            <div class="relative size-[150px]">
              <img :src="androidQr" alt="Android 端下载二维码" class="size-full rounded-[10px] object-contain" />
              <span
                class="absolute left-1/2 top-1/2 flex size-[42px] -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-[11px] bg-white"
              >
                <img :src="androidLogo" alt="Android" class="h-[22px] w-auto" />
              </span>
            </div>
            <div class="mt-[10px] whitespace-nowrap text-[12.5px] font-semibold text-[#42536e]">扫码下载 Android 端</div>
          </div>
        </div>
      </section>

      <!-- 页脚 -->
      <footer class="mt-[26px] flex items-center justify-between border-t border-black/[0.09] pt-[16px]">
        <div class="flex items-center gap-2.5 text-[12.5px] font-semibold text-[#5b6b84]">
          <template v-for="(platform, i) in platforms" :key="platform">
            <span>{{ platform }}</span>
            <span v-if="i < platforms.length - 1" class="size-[3px] rounded-full bg-[#c2cddd]" />
          </template>
        </div>
        <div class="flex items-center gap-2 text-[12.5px] font-medium text-[#7a89a1]">
          <svg class="size-[15px]" viewBox="0 0 16 16" fill="currentColor">
            <path
              d="M8 0C3.58 0 0 3.58 0 8a8 8 0 0 0 5.47 7.59c.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82a7.4 7.4 0 0 1 2-.27c.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z"
            />
          </svg>
          <span>github.com/Glassous/BetterHRBUST</span>
        </div>
      </footer>
    </div>
  </div>
</template>
