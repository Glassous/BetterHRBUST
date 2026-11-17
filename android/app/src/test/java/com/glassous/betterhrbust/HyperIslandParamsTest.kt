package com.glassous.betterhrbust

import com.glassous.betterhrbust.core.notify.CountdownIconRenderer
import com.glassous.betterhrbust.core.notify.HyperIslandParams
import com.glassous.betterhrbust.core.notify.IslandNotificationText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 岛参数结构与胶囊文案精简回归测试。
 */
class HyperIslandParamsTest {
    private fun sample(firstFloat: Boolean = true) = IslandNotificationText(
        title = "12 分钟后上课",
        content = "西-新B406（直播）",
        ticker = "数据结构与算法 · 12 分钟后上课",
        aodTitle = "数据结构与算法 西-新B406",
        countdownNumber = "12",
        countdownUnit = "分",
        room = HyperIslandParams.compactRoomForIsland("西-新B406（直播）"),
        timeoutMinutes = 13,
        islandTimeoutSeconds = 780,
        firstFloat = firstFloat
    )

    private fun paramV2(firstFloat: Boolean = true) =
        Json.parseToJsonElement(HyperIslandParams.build(sample(firstFloat)))
            .jsonObject["param_v2"]!!.jsonObject

    private fun island(firstFloat: Boolean = true) =
        paramV2(firstFloat)["param_island"]!!.jsonObject

    @Test
    fun `大岛左侧为纯文本倒计时且不含冲突应用图标`() {
        val left = island()["bigIslandArea"]!!.jsonObject["imageTextInfoLeft"]!!.jsonObject
        assertEquals(1, left["type"]!!.jsonPrimitive.int)
        assertNull(left["picInfo"])
        val text = left["textInfo"]!!.jsonObject
        assertEquals("12", text["title"]!!.jsonPrimitive.content)
        assertEquals("分", text["content"]!!.jsonPrimitive.content)
        assertEquals(true, text["showHighlightColor"]!!.jsonPrimitive.boolean)
        assertEquals(true, text["narrowFont"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `大岛右侧为去除括号后缀的精简教室名称`() {
        val right = island()["bigIslandArea"]!!.jsonObject["imageTextInfoRight"]!!.jsonObject
        assertEquals(2, right["type"]!!.jsonPrimitive.int)
        assertEquals("西-新B406", right["textInfo"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertNull(right["picInfo"])
    }

    @Test
    fun `小岛引用 pics 中的应用图标`() {
        val pic = island()["smallIslandArea"]!!.jsonObject["picInfo"]!!.jsonObject
        assertEquals(1, pic["type"]!!.jsonPrimitive.int)
        assertEquals(HyperIslandParams.PIC_KEY_LOGO, pic["pic"]!!.jsonPrimitive.content)
    }

    @Test
    fun `焦点通知卡片保留完整教室且按首次标记控制自动展开`() {
        val firstV2 = paramV2(firstFloat = true)
        val base = firstV2["baseInfo"]!!.jsonObject
        assertEquals("12 分钟后上课", base["title"]!!.jsonPrimitive.content)
        assertEquals("西-新B406（直播）", base["content"]!!.jsonPrimitive.content)
        assertEquals(13, firstV2["timeout"]!!.jsonPrimitive.int)
        assertEquals(true, firstV2["islandFirstFloat"]!!.jsonPrimitive.boolean)
        assertEquals(false, paramV2(firstFloat = false)["islandFirstFloat"]!!.jsonPrimitive.boolean)
        assertEquals(
            HyperIslandParams.ACTION_KEY_TIMETABLE,
            firstV2["hintInfo"]!!.jsonObject["actionInfo"]!!.jsonObject["action"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `胶囊教室名去除全半角括号备注并收敛待公布占位`() {
        assertEquals("西-新B406", HyperIslandParams.compactRoomForIsland("西-新B406（直播）"))
        assertEquals("东-主302", HyperIslandParams.compactRoomForIsland("东-主302 (多媒体) "))
        assertEquals("教1-302", HyperIslandParams.compactRoomForIsland("教1-302"))
        assertEquals("待公布", HyperIslandParams.compactRoomForIsland(""))
        assertEquals("待公布", HyperIslandParams.compactRoomForIsland("   "))
        assertEquals("待公布", HyperIslandParams.compactRoomForIsland("地点待公布"))
        assertEquals("（线上课程）", HyperIslandParams.compactRoomForIsland("（线上课程）"))
    }

    @Test
    fun `左侧单行倒计时简写格式正确`() {
        assertEquals("15分", CountdownIconRenderer.formatLabel(15))
        assertEquals("1分", CountdownIconRenderer.formatLabel(1))
        assertEquals("1分", CountdownIconRenderer.formatLabel(0))
    }
}
