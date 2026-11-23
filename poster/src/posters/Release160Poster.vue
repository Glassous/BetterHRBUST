<script setup lang="ts">
// v1.5.0 → v1.6.0 版本更新海报（1200 × 1600，竖屏 3:4）
// 三个重点：桌面的单周调课与自定义课程、Android 超级岛把教室挪到岛上、课程名录 / 培养方案修复
// 截图来自 poster/scripts/capture-web.mjs（模拟数据 + 无头浏览器），改文案直接改下面常量。
import androidQr from '../assets/android-qr.png'

const asset = (name: string) => `${import.meta.env.BASE_URL}${name}`
const iconUrl = asset('BetterHRBUST.png')
const qrUrl = androidQr
const timetableShot = asset('web-reschedule-mode.png')
const rescheduleShot = asset('web-reschedule-dialog.png')
const islandShot = asset('island-countdown.jpg')

const islandPoints = [
  { title: '折叠态', desc: '倒计时 + 教室' },
  { title: '展开态', desc: '课程 · 教师' },
  { title: '到点', desc: '自动收起' }
]

const fixes = [
  {
    index: '01',
    title: '课程名录',
    desc: '本学期在修课程改为本地缓存，离线也能翻；与历史成绩合并去重、按学期倒序，新增「在修 / 必修 / 选修」筛选与关键词搜索。'
  },
  {
    index: '02',
    title: '培养方案',
    desc: '已修课程按课程代码与名称比对成绩单，逐条标注「已通过 / 未修未过」；学分按类别结算，下拉即可看已修多少。'
  }
]

const platforms = ['网页', '油猴', 'Windows', 'Linux', 'Android', 'TUI']
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
      <div class="absolute -bottom-[280px] left-[40px] h-[700px] w-[700px] bg-[radial-gradient(closest-side,rgba(45,212,191,0.20),rgba(45,212,191,0))]" />
      <div
        class="absolute inset-0 opacity-60 [background-image:linear-gradient(to_right,rgba(15,23,42,0.05)_1px,transparent_1px),linear-gradient(to_bottom,rgba(15,23,42,0.05)_1px,transparent_1px)] [background-size:52px_52px] [mask-image:radial-gradient(72%_56%_at_50%_24%,#000_28%,transparent_100%)]"
      />
    </div>

    <div class="relative flex h-full flex-col px-[64px] pb-[36px] pt-[46px]">
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
      <div class="mt-[22px] flex items-center justify-between">
        <span
          class="rounded-full bg-[#1b64e0] px-3.5 py-[7px] text-[11px] font-bold uppercase tracking-[0.2em] text-white"
          >What's New</span
        >
        <div class="flex items-center gap-2.5">
          <span class="rounded-full bg-white/85 px-3.5 py-[7px] text-[14px] font-semibold text-[#8b98ae] ring-1 ring-inset ring-black/[0.07]"
            >v1.5.0</span
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
            >v1.6.0</span
          >
        </div>
      </div>

      <!-- 主标题 -->
      <h1 class="mt-[14px] text-[86px] font-black leading-[1.03] tracking-[-0.03em]">
        <span class="block">课表</span>
        <span class="relative mt-[2px] block">
          <span class="relative z-10 text-[#1b64e0]">自己排</span>
          <span
            class="absolute bottom-[-14px] left-[8px] z-0 h-[8px] w-[352px] rounded-full bg-[linear-gradient(90deg,#1b64e0,#7b4bf6)] opacity-85"
          />
        </span>
      </h1>

      <!-- 副标题 -->
      <p class="mt-[20px] text-[18.5px] font-medium leading-[1.6] text-[#42536e]">
        <span class="block">
          单周调课、实验与自定义课程，拖一下就落位，<span class="font-bold text-[#0b1526]">教务同步也不会覆盖</span>。
        </span>
        <span class="mt-[2px] block">
          课前 20 分钟，超级岛把<span class="font-bold text-[#0b1526]">「15 分」和「教1-302」</span>并排放好，赶路看岛就行。
        </span>
      </p>

      <!-- 01 · 桌面 / 网页课表 -->
      <section class="mt-[20px]">
        <div class="flex items-baseline gap-3">
          <span class="text-[11.5px] font-extrabold tracking-[0.16em] text-[#1b64e0]">01</span>
          <span class="text-[22px] font-bold tracking-[-0.01em]">桌面 / 网页 · 课表增强</span>
          <span class="text-[12.5px] font-medium text-[#7a89a1]">单周调课 · 实验与自定义课程</span>
        </div>

        <div class="mt-[12px] flex items-end gap-4">
          <!-- 课表截图 -->
          <div
            class="relative h-[452px] w-[720px] shrink-0 overflow-hidden rounded-[18px] bg-white shadow-[0_30px_60px_-34px_rgba(23,58,130,0.55),0_2px_10px_rgba(15,23,42,0.06)] ring-1 ring-white/80"
          >
            <img :src="timetableShot" alt="桌面端课表 · 调课模式" class="block h-full w-full object-cover object-top" />
            <!-- 底部渐隐，遮住截图的自然裁切边 -->
            <div class="pointer-events-none absolute inset-x-0 bottom-0 h-[72px] bg-[linear-gradient(to_top,#ffffff_8%,rgba(255,255,255,0))]" />
            <div class="absolute left-4 top-4 flex items-center gap-1.5 rounded-full bg-[#0b1526]/85 px-3 py-1.5 text-[11px] font-semibold text-white backdrop-blur">
              <span class="size-[6px] rounded-full bg-[#4ade80]" />
              调课模式 · 第 13 周
            </div>
          </div>

          <!-- 调课确认弹窗 -->
          <div class="relative min-w-0 flex-1">
            <img
              :src="rescheduleShot"
              alt="确认单周临时调课"
              class="block w-full rounded-[16px] shadow-[0_26px_48px_-24px_rgba(23,58,130,0.5),0_2px_8px_rgba(15,23,42,0.06)] ring-1 ring-white/70"
            />
            <p class="mt-2.5 text-center text-[11.5px] font-medium text-[#6b7a92]">
              教室 / 教师 / 备注都能单独改
            </p>
          </div>
        </div>

      </section>

      <!-- 02 · Android 超级岛升级 -->
      <section class="mt-[18px]">
        <div class="flex items-baseline gap-3">
          <span class="text-[11.5px] font-extrabold tracking-[0.16em] text-[#0ea5e9]">02</span>
          <span class="text-[22px] font-bold tracking-[-0.01em]">Android · 超级岛升级</span>
          <span class="text-[12.5px] font-medium text-[#7a89a1]">教室挪到岛上，赶路看岛就行</span>
        </div>

        <div
          class="relative mt-[12px] h-[200px] w-full overflow-hidden rounded-[22px] bg-[linear-gradient(118deg,#0a1020_0%,#111a30_58%,#1c1436_100%)] px-8 shadow-[0_30px_60px_-38px_rgba(11,16,26,0.9)]"
        >
          <div class="pointer-events-none absolute -left-[60px] -top-[120px] h-[320px] w-[320px] bg-[radial-gradient(closest-side,rgba(56,189,248,0.22),rgba(56,189,248,0))]" />
          <div class="pointer-events-none absolute -bottom-[140px] right-[120px] h-[360px] w-[360px] bg-[radial-gradient(closest-side,rgba(139,92,246,0.22),rgba(139,92,246,0))]" />

          <div class="relative flex h-full items-center justify-between gap-8">
            <div class="min-w-0">
              <div class="text-[21px] font-bold text-white">赶路的时候，看一眼岛就够了</div>
              <div class="mt-2.5 text-[15px] font-medium leading-[1.55] text-white/70">
                教室直接挪到倒计时旁边——还有几分钟、去哪间教室，不用掏手机翻课表。
              </div>
              <div class="mt-3.5 flex items-center gap-6">
                <template v-for="(item, i) in islandPoints" :key="item.title">
                  <div class="flex items-baseline gap-2">
                    <span class="text-[12.5px] font-bold tracking-[0.12em] text-[#7dd3fc]">{{ item.title }}</span>
                    <span class="text-[14.5px] font-medium text-white/70">{{ item.desc }}</span>
                  </div>
                  <span v-if="i < islandPoints.length - 1" class="h-[15px] w-px bg-white/15" />
                </template>
              </div>
            </div>

            <div class="shrink-0 rounded-[18px] bg-black/45 p-2.5 ring-1 ring-white/10 shadow-[0_20px_40px_-20px_rgba(0,0,0,0.9)]">
              <img
                :src="islandShot"
                alt="超级岛折叠态 · 15 分 教1-302"
                class="block h-[142px] w-auto rounded-[12px]"
              />
            </div>
          </div>
        </div>
      </section>

      <!-- 修复要点 + 二维码 -->
      <section class="mt-auto">
        <div class="flex items-center gap-4">
          <span class="text-[10.5px] font-extrabold uppercase tracking-[0.26em] text-[#95a3b8]">Fixes</span>
          <span class="h-px flex-1 bg-black/[0.09]" />
        </div>
        <div class="mt-[14px] flex items-start justify-between gap-10">
          <div class="grid flex-1 grid-cols-2">
            <div
              v-for="(item, i) in fixes"
              :key="item.index"
              :class="i === 0 ? 'pr-8' : 'border-l border-black/[0.07] pl-8'"
            >
              <div class="flex items-baseline gap-2.5">
                <span class="text-[11.5px] font-extrabold tracking-[0.16em] text-[#1b64e0]">{{ item.index }}</span>
                <span class="text-[19px] font-bold tracking-[-0.01em]">{{ item.title }}</span>
              </div>
              <p class="mt-[6px] text-[12.5px] font-medium leading-[1.72] text-[#5b6b84]">{{ item.desc }}</p>
            </div>
          </div>

          <div class="flex shrink-0 flex-col items-center">
            <div class="relative size-[130px]">
              <img :src="qrUrl" alt="Android 端下载二维码" class="size-full rounded-[10px] object-contain" />
              <span
                class="absolute left-1/2 top-1/2 flex size-[28px] -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-[8px] bg-white"
              >
                <img :src="iconUrl" alt="BetterHRBUST" class="h-[16px] w-auto" />
              </span>
            </div>
            <div class="mt-[8px] whitespace-nowrap text-[12px] font-semibold text-[#42536e]">扫码下载 Android 端</div>
          </div>
        </div>
      </section>

      <!-- 页脚 -->
      <footer class="mt-[16px] flex items-center justify-between border-t border-black/[0.09] pt-[14px]">
        <div class="flex items-center gap-2.5 text-[12px] font-semibold text-[#5b6b84]">
          <template v-for="(platform, i) in platforms" :key="platform">
            <span>{{ platform }}</span>
            <span v-if="i < platforms.length - 1" class="size-[3px] rounded-full bg-[#c2cddd]" />
          </template>
        </div>
        <div class="flex items-center gap-2 text-[12px] font-medium text-[#7a89a1]">
          <span class="rounded-full bg-white/70 px-2.5 py-1 ring-1 ring-inset ring-black/[0.05]">Android 1.6.0 · versionCode 8</span>
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
