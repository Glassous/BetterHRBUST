<script setup lang="ts">
// v1.3.0 → v1.4.0 版本更新海报（1200 × 1600，竖屏 3:4）
// 两个重点：Android 上课提示（超级岛 / 灵动岛）、Windows 命令行模式（BetterHRBUSTCLI）
// 内容全部硬编码：改文案直接改下面的常量或模板即可，不需要任何后台数据。
import androidLogo from '../assets/android-logo.svg'
import androidQr from '../assets/android-qr.png'

// 图片统一放在 public/，运行时按 BASE_URL 取绝对路径，构建后仍能离线打开
const asset = (name: string) => `${import.meta.env.BASE_URL}${name}`
const iconUrl = asset('BetterHRBUST.png')
const islandExpanded = asset('island-expanded.jpg')
const islandCapsule = asset('island-capsule.jpg')
const cliShot = asset('cli-exams.png')

// 顺序与面板里的位置一致：左大图为展开态，右小图为折叠态
const islands = [
  { label: '展开态 · 卡片', desc: '课程、教室、教师一眼看全' },
  { label: '折叠态 · 胶囊', desc: '只留一句话，安静地待在状态栏' }
]

const cliPoints = [
  '只读本地缓存：不联网、不写数据、不启动服务',
  '任意命令加 --json，脚本与 AI 直接消费',
  'check --strict 当 CI 门禁，skill 装进 AI 工具'
]

const others = [
  { index: '01', title: '课表解析修复', desc: '按周次行锚定重算，已结束课程不再标成正常色' },
  { index: '02', title: '界面细节打磨', desc: '课表展开动画更顺滑，油猴详情弹层样式修复' }
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

    <div class="relative flex h-full flex-col px-[64px] pb-[40px] pt-[46px]">
      <!-- 品牌头 -->
      <div class="flex items-start justify-between">
        <div class="flex items-center gap-3.5">
          <img
            :src="iconUrl"
            alt="BetterHRBUST"
            class="h-[70px] w-[70px] object-contain drop-shadow-[0_12px_20px_rgba(27,100,224,0.28)]"
          />
          <div>
            <div class="text-[23px] font-extrabold leading-none tracking-[-0.02em]">BetterHRBUST</div>
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
      <div class="mt-[26px] flex items-center justify-between">
        <span
          class="rounded-full bg-[#1b64e0] px-3.5 py-[7px] text-[11px] font-bold uppercase tracking-[0.2em] text-white"
          >What's New</span
        >
        <div class="flex items-center gap-2.5">
          <span class="rounded-full bg-white/85 px-3.5 py-[7px] text-[14px] font-semibold text-[#8b98ae] ring-1 ring-inset ring-black/[0.07]"
            >v1.3.0</span
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
            >v1.4.0</span
          >
        </div>
      </div>

      <!-- 主标题 -->
      <h1 class="mt-[18px] text-[88px] font-black leading-[1.03] tracking-[-0.03em]">
        <span class="block">上课提示</span>
        <span class="relative mt-[4px] block">
          <span class="relative z-10 text-[#1b64e0]">命令行模式</span>
          <span
            class="absolute bottom-[-16px] left-[8px] z-0 h-[8px] w-[440px] rounded-full bg-[linear-gradient(90deg,#1b64e0,#7b4bf6)] opacity-85"
          />
        </span>
      </h1>

      <!-- 副标题：手动断行，避免中文在任意位置折行 -->
      <p class="mt-[22px] text-[20px] font-medium leading-[1.6] text-[#42536e]">
        <span class="block">
          课前 20 分钟提醒课程与教室，<span class="font-bold text-[#0b1526]">小米超级岛、实况通知、普通通知</span>按设备自动降级
        </span>
        <span class="mt-[2px] block">
          Windows 新增 <span class="font-bold text-[#0b1526]">BetterHRBUSTCLI</span> —— 课表、成绩、考试，搬进终端。
        </span>
      </p>

      <!-- 01 · Android 上课提示 -->
      <section class="mt-[22px]">
        <div class="flex items-baseline gap-3">
          <span class="text-[11.5px] font-extrabold tracking-[0.16em] text-[#1b64e0]">01</span>
          <span class="text-[22px] font-bold tracking-[-0.01em]">Android · 上课提示</span>
          <span class="text-[12.5px] font-medium text-[#7a89a1]">全设备可用，默认开启，上课后自动收起</span>
        </div>

        <div
          class="relative mt-[14px] h-[400px] w-full overflow-hidden rounded-[26px] bg-[linear-gradient(135deg,#f4f8ff_0%,#e8efff_58%,#e4ebff_100%)] shadow-[inset_0_0_0_1px_rgba(255,255,255,0.9),0_30px_60px_-40px_rgba(23,58,130,0.5)]"
        >
          <div class="pointer-events-none absolute -left-[80px] -top-[120px] h-[420px] w-[420px] bg-[radial-gradient(closest-side,rgba(59,130,246,0.22),rgba(59,130,246,0))]" />
          <div class="pointer-events-none absolute -bottom-[140px] right-[60px] h-[420px] w-[420px] bg-[radial-gradient(closest-side,rgba(139,92,246,0.18),rgba(139,92,246,0))]" />

          <!-- 标签：左边只标系统版本，右边把「小米超级岛」做成徽标强调 -->
          <div class="absolute left-[36px] top-[20px] flex items-center gap-2.5">
            <span
              class="rounded-full bg-white/85 px-3 py-[6px] text-[11.5px] font-semibold text-[#33415c] ring-1 ring-inset ring-black/[0.05]"
              >小米澎湃 OS 4</span
            >
            <span
              class="flex items-center gap-2 rounded-full bg-gradient-to-r from-[#1b64e0] to-[#7b4bf6] px-4 py-[7px] text-[15px] font-extrabold tracking-[-0.01em] text-white shadow-[0_12px_22px_-12px_rgba(79,70,229,0.95)]"
            >
              <span class="size-[6px] rounded-full bg-white/90" />
              小米超级岛
            </span>
          </div>

          <!-- 展开态：主视觉，占满左侧 -->
          <div
            class="absolute left-[36px] top-[62px] w-[640px] overflow-hidden rounded-[18px] bg-[#0f1116] shadow-[0_36px_60px_-30px_rgba(15,23,42,0.5),0_8px_18px_-12px_rgba(15,23,42,0.2)]"
          >
            <img :src="islandExpanded" alt="上课提示 · 超级岛展开态" class="block w-full" />
          </div>

          <!-- 折叠态：右侧胶囊特写。原图是整条状态栏，裁掉两侧壁纸只保留岛本体 -->
          <div
            class="absolute left-[726px] top-[122px] h-[182px] w-[300px] overflow-hidden rounded-[24px] bg-[#0f1116] shadow-[0_26px_48px_-26px_rgba(15,23,42,0.5),0_6px_16px_-10px_rgba(15,23,42,0.18)]"
          >
            <img :src="islandCapsule" alt="上课提示 · 超级岛折叠态" class="block h-full w-full object-cover object-center" />
          </div>

          <!-- 形态说明 -->
          <div class="absolute left-[36px] top-[374px] flex items-center gap-3">
            <template v-for="(item, i) in islands" :key="item.label">
              <div class="flex items-baseline gap-2">
                <span class="text-[12.5px] font-bold text-[#1b64e0]">{{ item.label }}</span>
                <span class="text-[12px] font-medium text-[#5b6b84]">{{ item.desc }}</span>
              </div>
              <span v-if="i < islands.length - 1" class="h-[13px] w-px bg-black/[0.12]" />
            </template>
          </div>
        </div>
      </section>

      <!-- 02 · Windows 命令行 -->
      <section class="mt-[18px]">
        <div class="flex items-baseline gap-3">
          <span class="text-[11.5px] font-extrabold tracking-[0.16em] text-[#1b64e0]">02</span>
          <span class="text-[22px] font-bold tracking-[-0.01em]">Windows · 命令行模式</span>
          <span class="text-[12.5px] font-medium text-[#7a89a1]">安装包与便携版均已内置</span>
        </div>

        <div
          class="mt-[14px] flex h-[222px] w-full items-stretch gap-[26px] overflow-hidden rounded-[24px] bg-[#0b0f17] px-[24px] py-[14px] shadow-[0_34px_60px_-38px_rgba(11,16,26,0.85)]"
        >
          <!-- 终端窗口 -->
          <div class="flex w-[600px] shrink-0 flex-col overflow-hidden rounded-[14px] bg-[#07090d] ring-1 ring-white/[0.09]">
            <div class="flex h-[34px] shrink-0 items-center gap-2 border-b border-white/[0.08] px-3.5">
              <span class="size-[9px] rounded-full bg-[#ff5f57]" />
              <span class="size-[9px] rounded-full bg-[#febc2e]" />
              <span class="size-[9px] rounded-full bg-[#28c840]" />
              <span class="ml-2 text-[11px] font-medium text-white/45">Windows PowerShell — BetterHRBUSTCLI</span>
            </div>
            <div class="flex flex-1 items-start px-[10px] py-[10px]">
              <img :src="cliShot" alt="BetterHRBUSTCLI 查询考试日程" class="block w-full rounded-[6px]" />
            </div>
          </div>

          <!-- 命令行要点 -->
          <div class="flex min-w-0 flex-1 flex-col justify-center gap-[13px]">
            <div v-for="point in cliPoints" :key="point" class="flex items-start gap-2.5">
              <span class="mt-[7px] size-[5px] shrink-0 rounded-full bg-[#7dd3fc]" />
              <span class="text-[13.5px] font-medium leading-[1.55] text-white/75">{{ point }}</span>
            </div>
            <div class="mt-[2px] flex items-center gap-2 text-[12px] font-semibold text-white/40">
              <span>BetterHRBUSTCLI status</span>
              <span class="size-[3px] rounded-full bg-white/25" />
              <span>timetable</span>
              <span class="size-[3px] rounded-full bg-white/25" />
              <span>scores</span>
              <span class="size-[3px] rounded-full bg-white/25" />
              <span>check</span>
            </div>
          </div>
        </div>
      </section>

      <!-- 更新要点 + 下载二维码 -->
      <section class="mt-auto">
        <div class="flex items-center gap-4">
          <span class="text-[10.5px] font-extrabold uppercase tracking-[0.26em] text-[#95a3b8]">Highlights</span>
          <span class="h-px flex-1 bg-black/[0.09]" />
        </div>
        <div class="mt-[16px] flex items-start justify-between gap-12">
          <!-- 左侧要点：两列，不与二维码共处一个表格 -->
          <div class="grid flex-1 grid-cols-2">
            <div
              v-for="(item, i) in others"
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
            <div class="relative size-[140px]">
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
      <footer class="mt-[18px] flex items-center justify-between border-t border-black/[0.09] pt-[16px]">
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
