package com.glassous.betterhrbust

import com.glassous.betterhrbust.core.notify.HyperIslandParams
import com.glassous.betterhrbust.core.notify.IslandNotificationText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 岛参数结构回归测试。
 *
 * 依据《小米超级岛摘要态设计规范》：A 区（左）可只放文字、B 区（右）可用纯文本组件、
 * 小岛只接受图片组件。结构写错时系统会整段忽略 param_island，通知退回普通样式
 * （表现为「看不到超级岛」）。
 */
class HyperIslandParamsTest {
    private fun sample() = IslandNotificationText(
        title = "12 分钟后上课",
        content = "教1-302",
        ticker = "数据结构与算法 · 12 分钟后上课",
        aodTitle = "数据结构与算法 教1-302",
        countdownNumber = "12",
        countdownUnit = "分钟",
        room = "教1-302",
        timeoutMinutes = 13,
        islandTimeoutSeconds = 780
    )

    private fun island() = Json.parseToJsonElement(HyperIslandParams.build(sample()))
        .jsonObject["param_v2"]!!.jsonObject["param_island"]!!.jsonObject

    @Test
    fun `大岛左侧为倒计时图文`() {
        val left = island()["bigIslandArea"]!!.jsonObject["imageTextInfoLeft"]!!.jsonObject
        assertEquals(1, left["type"]!!.jsonPrimitive.int)
        // 参考实现的「左图右文」组合里 A 区必须带图，否则 B 区会被系统裁掉
        assertEquals(
            HyperIslandParams.PIC_KEY_LOGO,
            left["picInfo"]!!.jsonObject["pic"]!!.jsonPrimitive.content
        )
        val text = left["textInfo"]!!.jsonObject
        assertEquals("12", text["title"]!!.jsonPrimitive.content)
        assertEquals("分钟", text["content"]!!.jsonPrimitive.content)
        assertEquals(true, text["showHighlightColor"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `大岛右侧为图文组件2的文字槽位`() {
        val right = island()["bigIslandArea"]!!.jsonObject["imageTextInfoRight"]!!.jsonObject
        assertEquals(2, right["type"]!!.jsonPrimitive.int)
        assertEquals("教1-302", right["textInfo"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertNull(right["picInfo"])
    }

    @Test
    fun `小岛引用 pics 中的应用图标`() {
        // 小岛实测只渲染图片，图文组件里的文字会被系统丢弃，折叠态因此只保留应用图标
        val pic = island()["smallIslandArea"]!!.jsonObject["picInfo"]!!.jsonObject
        assertEquals(1, pic["type"]!!.jsonPrimitive.int)
        assertEquals(HyperIslandParams.PIC_KEY_LOGO, pic["pic"]!!.jsonPrimitive.content)
    }

    @Test
    fun `焦点通知卡片主副文案`() {
        val v2 = Json.parseToJsonElement(HyperIslandParams.build(sample()))
            .jsonObject["param_v2"]!!.jsonObject
        val base = v2["baseInfo"]!!.jsonObject
        assertEquals("12 分钟后上课", base["title"]!!.jsonPrimitive.content)
        assertEquals("教1-302", base["content"]!!.jsonPrimitive.content)
        assertEquals(13, v2["timeout"]!!.jsonPrimitive.int)
        assertEquals(
            HyperIslandParams.ACTION_KEY_TIMETABLE,
            v2["hintInfo"]!!.jsonObject["actionInfo"]!!.jsonObject["action"]!!.jsonPrimitive.content
        )
    }
}
