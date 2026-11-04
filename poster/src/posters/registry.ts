import type { Component } from 'vue'
import Release130Poster from './Release130Poster.vue'

export interface PosterItem {
  /** 唯一 id，同时用作组件 key */
  id: string
  /** 列表里显示的名称 */
  name: string
  /** 列表里显示的副标题（版本号 / 主题） */
  meta: string
  /** 画布尺寸（导出按 2 倍图） */
  width: number
  height: number
  /** 下载文件名 */
  fileName: string
  component: Component
}

// 海报清单：新增海报时写一个 xxxPoster.vue（固定宽高、内容硬编码），在这里追加一项即可。
// 只有一张海报时不会显示切换按钮。
export const posters: PosterItem[] = [
  {
    id: 'release-1.3.0',
    name: '版本更新 · 空教室查询',
    meta: 'v1.2.0 → v1.3.0',
    width: 1200,
    height: 1600,
    fileName: 'BetterHRBUST-v1.3.0-空教室查询.png',
    component: Release130Poster
  }
]
