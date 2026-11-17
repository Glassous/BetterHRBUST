<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { gsap } from 'gsap'
import PlatformIcon from './PlatformIcon.vue'
import {
  ANDROID_APK,
  DESKTOP_ALL,
  LINUX_DEB,
  LINUX_RPM,
  RELEASES,
  USERSCRIPT,
  WINDOWS_PORTABLE,
  WINDOWS_SETUP
} from '../links.js'

const root = ref(null)
let ctx
let observers = []

// 六端卡片：真实品牌 SVG 图标 + 一句话说明 + 真实跳转链接；未上线平台以 soon 标记「敬请期待」
// logoClass 单独给：Android 机器人头为宽幅，Apple / Linux(Tux) 为竖向比例，各自约束以对齐视觉重量
const platforms = [
  {
    name: '油猴脚本',
    logo: '/Tampermonkey_logo.svg',
    logoClass: 'h-14 w-14',
    desc: '一键安装，自动接管旧版教务页面',
    links: [{ label: '一键安装', href: USERSCRIPT, primary: true }]
  },
  {
    name: 'Windows 桌面版',
    logo: '/Windows_logo.svg',
    logoClass: 'h-14 w-14',
    desc: '独立桌面应用，离线可用',
    links: [
      { label: '下载安装版', href: WINDOWS_SETUP, primary: true },
      { label: '便携版 ZIP', href: WINDOWS_PORTABLE, primary: false },
      { label: '含 Linux 整合包', href: DESKTOP_ALL, primary: false }
    ]
  },
  {
    name: 'Android 版',
    logo: '/Android_logo.svg',
    logoClass: 'w-20',
    desc: '独立Android应用，离线可用',
    links: [{ label: '下载 APK', href: ANDROID_APK, primary: true }]
  },
  {
    name: 'Linux 版',
    logo: '/Linux_logo.svg',
    logoClass: 'h-16 w-auto',
    desc: 'deb / rpm 双发行版，与 Windows 同一套内核',
    links: [
      { label: '下载整合包', href: DESKTOP_ALL, primary: true },
      { label: 'deb', href: LINUX_DEB, primary: false },
      { label: 'rpm', href: LINUX_RPM, primary: false }
    ]
  },
  {
    name: 'Mac 版',
    logo: '/Apple_logo.svg',
    logoClass: 'h-16 w-auto',
    desc: 'macOS 原生客户端，正在开发中',
    links: [{ label: '敬请期待', soon: true }]
  },
  {
    name: 'App Store',
    logo: '/App_Store_logo.svg',
    logoClass: 'h-14 w-14',
    desc: 'iPhone / iPad 版本，准备上架中',
    links: [{ label: '敬请期待', soon: true }]
  }
]

// 入场动画：元素先隐藏，进入视口后依次淡入上移，只播放一次。
// 用 IntersectionObserver 触发而非滚动库，行为可预测且零额外依赖；
// 用户偏好减弱动效或环境不支持 IO 时，元素保持默认可见。
onMounted(() => {
  if (!root.value) return
  if (typeof IntersectionObserver === 'undefined') return
  if (window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches) return

  observers = []

  ctx = gsap.context(() => {
    const groups = [
      { trigger: '.section-head', items: '.section-head > *', stagger: 0.12 },
      { trigger: '.platform-grid', items: '.platform-card', stagger: 0.12 }
    ]

    groups.forEach(({ trigger, items, stagger }) => {
      const triggerEl = root.value.querySelector(trigger)
      const itemEls = root.value.querySelectorAll(items)
      if (!triggerEl || !itemEls.length) return

      gsap.set(itemEls, { autoAlpha: 0, y: 46 })

      const observer = new IntersectionObserver(
        (entries, obs) => {
          entries.forEach((entry) => {
            if (!entry.isIntersecting) return
            gsap.to(itemEls, {
              autoAlpha: 1,
              y: 0,
              duration: 0.8,
              ease: 'power3.out',
              stagger
            })
            obs.disconnect()
          })
        },
        { threshold: 0.15, rootMargin: '0px 0px -40px 0px' }
      )

      observer.observe(triggerEl)
      observers.push(observer)
    })
  }, root.value)
})

onUnmounted(() => {
  observers.forEach((observer) => observer.disconnect())
  observers = []
  ctx?.revert()
})
</script>

<template>
  <section id="platforms" ref="root" class="relative px-6 pb-28 pt-10 sm:pt-16">
    <div class="mx-auto max-w-6xl">
      <!-- 区标题 -->
      <div class="section-head mx-auto max-w-2xl text-center">
        <p class="text-xs font-semibold uppercase tracking-[0.28em] text-brand-300/90">
          Everywhere
        </p>
        <h2 class="mt-4 text-3xl font-extrabold tracking-tight text-white sm:text-4xl">
          随处可用
        </h2>
        <p class="mt-4 text-base leading-relaxed text-white/55">
          同一套业务内核与数据口径，选你习惯的设备打开校园生活
        </p>
      </div>

      <!-- 三端卡片 -->
      <div class="platform-grid mt-14 grid gap-5 sm:grid-cols-2 lg:grid-cols-3 lg:gap-6">
        <article
          v-for="platform in platforms"
          :key="platform.name"
          class="platform-card glass-card group flex flex-col rounded-3xl p-7 transition-all duration-300 hover:-translate-y-1.5 hover:border-white/20 hover:shadow-[0_32px_70px_-32px_rgba(99,102,241,0.6)] sm:p-8"
        >
          <div
            class="icon-glow flex h-24 w-24 items-center justify-center rounded-[1.6rem] bg-ivory"
          >
            <img
              :src="platform.logo"
              alt=""
              draggable="false"
              :class="[
                platform.logoClass,
                'select-none transition-transform duration-300 group-hover:scale-110'
              ]"
            />
          </div>

          <h3 class="mt-7 text-2xl font-bold text-white">{{ platform.name }}</h3>
          <p class="mt-2.5 text-[15px] leading-relaxed text-white/55">{{ platform.desc }}</p>

          <div class="mt-auto flex flex-wrap items-center gap-x-6 gap-y-2 pt-7">
            <template v-for="link in platform.links" :key="link.href ?? link.label">
              <!-- 未上线平台：只展示状态，不可点击 -->
              <span
                v-if="link.soon"
                class="inline-flex cursor-default select-none items-center gap-1.5 text-sm font-medium text-white/30"
              >
                {{ link.label }}
                <PlatformIcon name="clock" class="h-3.5 w-3.5" />
              </span>
              <a
                v-else
                :href="link.href"
                target="_blank"
                rel="noopener noreferrer"
                :class="
                  link.primary
                    ? 'group/link inline-flex items-center gap-1.5 text-sm font-semibold text-brand-300 transition-colors hover:text-brand-200'
                    : 'inline-flex items-center gap-1.5 text-sm font-medium text-white/40 transition-colors hover:text-white/70'
                "
              >
                {{ link.label }}
                <PlatformIcon
                  :name="link.primary ? 'arrow-right' : 'download'"
                  :class="
                    link.primary
                      ? 'h-4 w-4 transition-transform duration-300 group-hover/link:translate-x-1'
                      : 'h-3.5 w-3.5'
                  "
                />
              </a>
            </template>
          </div>
        </article>
      </div>

      <p class="mt-12 text-center text-sm text-white/40">
        更多历史版本与更新日志：
        <a
          :href="RELEASES"
          target="_blank"
          rel="noopener noreferrer"
          class="font-medium text-white/60 underline decoration-white/20 underline-offset-4 transition-colors hover:text-white"
        >
          GitHub Releases
        </a>
      </p>
    </div>
  </section>
</template>
