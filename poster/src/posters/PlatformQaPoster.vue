<script setup lang="ts">
// 平台答疑海报（1200 × 1600，竖屏 3:4）
// 四个问题：MacOS、iOS、鸿蒙、网页版。内容全部硬编码，改文案直接改下面的模板。
const platforms = ['Android', 'Windows', 'Linux', '油猴']

// 图片统一放在 public/，运行时按 BASE_URL 取绝对路径，构建后仍能离线打开
const asset = (name: string) => `${import.meta.env.BASE_URL}${name}`
const iconUrl = asset('BetterHRBUST.png')
</script>

<template>
  <div class="poster-sheet relative h-[1600px] w-[1200px] overflow-hidden bg-[#eef3ff] text-[#0b1526]">
    <!-- 顶部彩色细条 -->
    <div class="absolute inset-x-0 top-0 h-[7px] bg-gradient-to-r from-[#1b64e0] via-[#4d63f5] to-[#8b5cf6]" />

    <!-- 背景：浅色渐变 + 三处光斑 + 网格（与版本海报一致） -->
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
          <span class="text-[12.5px] font-semibold text-[#33415c]">平台答疑</span>
        </div>
      </div>

      <!-- 小标 + 问题数量 -->
      <div class="mt-[24px] flex items-center justify-between">
        <span
          class="rounded-full bg-[#1b64e0] px-3.5 py-[7px] text-[11px] font-bold uppercase tracking-[0.2em] text-white"
          >Q &amp; A</span
        >
        <div class="flex items-center gap-2.5">
          <span class="rounded-full bg-white/85 px-3.5 py-[7px] text-[14px] font-semibold text-[#5b6b84] ring-1 ring-inset ring-black/[0.07]"
            >4 个问题</span
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
            >逐条答复</span
          >
        </div>
      </div>

      <!-- 主标题 -->
      <h1 class="mt-[18px] text-[88px] font-black leading-[1.03] tracking-[-0.03em]">
        <span class="block">平台支持</span>
        <span class="relative mt-[4px] block">
          <span class="relative z-10 text-[#1b64e0]">一次说清</span>
          <span
            class="absolute bottom-[-16px] left-[8px] z-0 h-[8px] w-[352px] rounded-full bg-[linear-gradient(90deg,#1b64e0,#7b4bf6)] opacity-85"
          />
        </span>
      </h1>

      <p class="mt-[22px] text-[20px] font-medium leading-[1.6] text-[#42536e]">
        关于 MacOS、iOS、鸿蒙与网页版，这里给出明确答复。
      </p>

      <!-- 四条问答：直接排在页面上，无卡片容器、无图标 -->
      <div class="mt-[26px] flex flex-1 flex-col divide-y divide-black/[0.09] border-t border-black/[0.09]">
        <!-- Q1 · MacOS -->
        <article class="flex flex-1 items-center">
          <div class="w-[320px] shrink-0">
            <div class="text-[28px] font-black tracking-[0.14em] text-[#1b64e0]">Q1</div>
            <div class="mt-[6px] text-[54px] font-black leading-none tracking-[-0.03em]">MacOS</div>
            <div class="mt-[12px] text-[11.5px] font-bold tracking-[0.18em] text-[#95a3b8]">DESKTOP CLIENT</div>
          </div>
          <div class="my-[24px] w-px self-stretch bg-black/[0.1]" />
          <div class="flex-1 pl-[40px]">
            <div class="text-[46px] font-black leading-[1.08] tracking-[-0.02em]">在路上了</div>
            <p class="mt-[14px] text-[24px] font-medium leading-[1.7] text-[#4b5b73]">
              桌面端版本已经在开发中，完成后会同步发布。
            </p>
          </div>
        </article>

        <!-- Q2 · iOS -->
        <article class="flex flex-1 items-center">
          <div class="w-[320px] shrink-0">
            <div class="text-[28px] font-black tracking-[0.14em] text-[#5b6b84]">Q2</div>
            <div class="mt-[6px] text-[54px] font-black leading-none tracking-[-0.03em]">iOS</div>
            <div class="mt-[12px] text-[11.5px] font-bold tracking-[0.18em] text-[#95a3b8]">IPHONE · IPAD</div>
          </div>
          <div class="my-[24px] w-px self-stretch bg-black/[0.1]" />
          <div class="flex-1 pl-[40px]">
            <div class="text-[46px] font-black leading-[1.08] tracking-[-0.02em]">暂时做不了</div>
            <p class="mt-[14px] text-[24px] font-medium leading-[1.7] text-[#4b5b73]">
              作者没有 MacOS 的电脑，也没有苹果手机可以测试。如果有开发者能做，并且能帮助上架 App Store，非常欢迎。
            </p>
          </div>
        </article>

        <!-- Q3 · 鸿蒙 -->
        <article class="flex flex-1 items-center">
          <div class="w-[320px] shrink-0">
            <div class="text-[28px] font-black tracking-[0.14em] text-[#0f9b8e]">Q3</div>
            <div class="mt-[6px] text-[54px] font-black leading-none tracking-[-0.03em]">鸿蒙</div>
            <div class="mt-[12px] text-[11.5px] font-bold tracking-[0.18em] text-[#95a3b8]">HARMONYOS</div>
          </div>
          <div class="my-[24px] w-px self-stretch bg-black/[0.1]" />
          <div class="flex-1 pl-[40px]">
            <div class="text-[46px] font-black leading-[1.08] tracking-[-0.02em]">暂无开发计划</div>
            <p class="mt-[14px] text-[24px] font-medium leading-[1.7] text-[#4b5b73]">
              建议直接安装 Android 版使用。
            </p>
          </div>
        </article>

        <!-- Q4 · 网页版 -->
        <article class="flex flex-1 items-center">
          <div class="w-[320px] shrink-0">
            <div class="text-[28px] font-black tracking-[0.14em] text-[#7b4bf6]">Q4</div>
            <div class="mt-[6px] text-[54px] font-black leading-none tracking-[-0.03em]">网页版</div>
            <div class="mt-[12px] text-[11.5px] font-bold tracking-[0.18em] text-[#95a3b8]">WEB · SELF-HOST</div>
          </div>
          <div class="my-[24px] w-px self-stretch bg-black/[0.1]" />
          <div class="flex-1 pl-[40px]">
            <div class="text-[46px] font-black leading-[1.08] tracking-[-0.02em]">并非无解，但要自己部署</div>
            <p class="mt-[14px] text-[24px] font-medium leading-[1.7] text-[#4b5b73]">
              受一些浏览器相关的神秘特性影响，理论上网页版其实是最难实现的。作者已经做好了一个，但为避免隐私争议不对外提供；<span
                class="font-bold text-[#0b1526]"
                >自部署工具链和教程都已经备好</span
              >，动手能力强的同学可以试试。
            </p>
          </div>
        </article>
      </div>

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
