package com.glassous.betterhrbust

import com.glassous.betterhrbust.core.network.AcademicHttpClient
import com.glassous.betterhrbust.core.network.CharsetDecoderHelper
import com.glassous.betterhrbust.core.network.SessionCookieJar
import com.glassous.betterhrbust.core.parser.AcademicParsers
import com.glassous.betterhrbust.data.repository.AcademicRepository
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.Charset

class NetworkAndSessionTest {

    @Test
    fun cookiesWithSameNameKeepTheirPathsAndExpiredSessionIsRemoved() {
        val jar = SessionCookieJar()
        val url = "http://jwzx.hrbust.edu.cn/academic/login.jsp".toHttpUrl()
        val root = Cookie.Builder().name("JSESSIONID").value("root")
            .domain(url.host).path("/").build()
        val academic = root.newBuilderForTest("academic", "/academic")
        jar.saveFromResponse(url, listOf(root, academic))
        assertEquals(listOf("academic", "root"), jar.loadForRequest(url).map { it.value })
        val expired = Cookie.Builder().name("JSESSIONID").value("deleted")
            .domain(url.host).path("/academic").expiresAt(0).build()
        jar.saveFromResponse(url, listOf(expired))
        assertEquals(listOf("root"), jar.loadForRequest(url).map { it.value })
        jar.saveFromResponse(url, listOf(Cookie.Builder().name("JSESSIONID").value("deleted")
            .domain(url.host).path("/").expiresAt(0).build()))
        assertFalse(jar.hasSession())
        assertNull(jar.getJSessionId())
    }

    private fun Cookie.newBuilderForTest(value: String, path: String): Cookie =
        Cookie.Builder().name(name).value(value).domain(domain).path(path).build()

    @Test
    fun testSessionCookieJarStorageAndRetrieval() {
        val cookieJar = SessionCookieJar()
        val url = "http://jwzx.hrbust.edu.cn/academic/login.jsp".toHttpUrl()

        assertFalse(cookieJar.hasSession())

        val cookie = Cookie.Builder()
            .name("JSESSIONID")
            .value("ABC123456789")
            .domain("jwzx.hrbust.edu.cn")
            .path("/academic")
            .build()

        cookieJar.saveFromResponse(url, listOf(cookie))

        assertTrue(cookieJar.hasSession())
        assertEquals("ABC123456789", cookieJar.getJSessionId())

        val loadedCookies = cookieJar.loadForRequest(url)
        assertEquals(1, loadedCookies.size)
        assertEquals("JSESSIONID", loadedCookies[0].name)
        assertEquals("ABC123456789", loadedCookies[0].value)

        cookieJar.clear()
        assertFalse(cookieJar.hasSession())
        assertTrue(cookieJar.loadForRequest(url).isEmpty())
    }

    @Test
    fun testCharsetDecoderHelperGbkAndUtf8() {
        val originalText = "哈尔滨理工大学 综合教务在线 2026"

        // GBK encoded bytes
        val gbkBytes = originalText.toByteArray(Charset.forName("GBK"))
        val decodedFromGbk = CharsetDecoderHelper.decode(gbkBytes, "text/html; charset=gbk")
        assertEquals(originalText, decodedFromGbk)

        // UTF-8 encoded bytes
        val utf8Bytes = originalText.toByteArray(Charsets.UTF_8)
        val decodedFromUtf8 = CharsetDecoderHelper.decode(utf8Bytes, "text/html; charset=utf-8")
        assertEquals(originalText, decodedFromUtf8)

        // URL encoding with GBK
        val encodedParam = CharsetDecoderHelper.encodeGbk("高等数学")
        assertTrue(encodedParam.contains("%"))
    }

    @Test
    fun explicitUtf8HeaderOverridesGbkFallbackAndMetaTag() {
        val utf8Html = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=GBK\"></head><body>第二课堂（2023） 选课属性：必修 学分要求=2.0 门数要求=2</body></html>"
        val utf8Bytes = utf8Html.toByteArray(Charsets.UTF_8)

        val decoded = CharsetDecoderHelper.decode(
            bytes = utf8Bytes,
            contentTypeHeader = "text/html;charset=UTF-8",
            preferredCharset = CharsetDecoderHelper.GBK
        )
        assertTrue("应优先遵从响应头 UTF-8，避免整页解码为乱码", decoded.contains("选课属性：必修"))
        assertTrue(decoded.contains("第二课堂（2023）"))

        val gbkHtml = "<html><body>哈尔滨理工大学 个人教学计划</body></html>"
        val gbkDecoded = CharsetDecoderHelper.decode(
            bytes = gbkHtml.toByteArray(CharsetDecoderHelper.GBK),
            contentTypeHeader = "text/html",
            preferredCharset = CharsetDecoderHelper.GBK
        )
        assertEquals(gbkHtml, gbkDecoded)
    }

    @Test
    fun curriculumPlanRequestPreservesPlusInEncryptedStudentIdAndDecodesUtf8() = runBlocking {
        // 验证与 Web 端 api.js 完全一致：加密串中的 `+` 不得被 URLDecoder 转为空格
        val rawPlusId = "abc+def/ghi=="
        assertEquals(
            "manager/studyschedule/studentScheduleShowByTerm.do?z=z&studentId=abc%2Bdef%2Fghi%3D%3D",
            AcademicRepository.buildCurriculumPlanUrl(rawPlusId)
        )
        assertEquals(
            "manager/studyschedule/studentScheduleShowByTerm.do?z=z&studentId=abc%2Bdef%2Fghi%3D%3D",
            AcademicRepository.buildCurriculumPlanUrl("abc%2Bdef%2Fghi%3D%3D")
        )

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var receivedQueryStudentId: String? = null
        server.createContext("/academic/manager/studyschedule/studentSelfSchedule.jsdo") { exchange ->
            val html = """<a href="/academic/manager/studyschedule/scheduleJump.jsp?link=studentScheduleShowByTerm.do&studentId=$rawPlusId&classId=28008">学期模式</a>"""
            val bytes = html.toByteArray(CharsetDecoderHelper.GBK)
            exchange.responseHeaders.add("Content-Type", "text/html;charset=gbk")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/academic/manager/studyschedule/studentScheduleShowByTerm.do") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            val rawParam = query.split("&").firstOrNull { it.startsWith("studentId=") }?.substringAfter("studentId=").orEmpty()
            receivedQueryStudentId = URLDecoder.decode(rawParam, "UTF-8")
            val planHtml = """
                <html><head><meta http-equiv="Content-Type" content="text/html; charset=GBK"></head>
                <body>
                  <select id="syt12"><option value="229885118">学科基础课程（2023） 选课属性：必修 学分要求=26.5 门数要求=8</option></select>
                  <table id="output_ctx">
                    <tr><th>课程号</th><th>课程名</th><th>考核方式</th><th>学分</th><th>学时</th><th>课程类别</th><th>课组</th><th>课组要求</th></tr>
                    <tr><td>U040023XN01W1</td><td>程序设计基础</td><td>集中</td><td>4</td><td>64</td><td>学位课</td><td><a href="g?sgid=229885118">学科基础课程（2023）</a></td><td>必修</td></tr>
                  </table>
                </body></html>
            """.trimIndent()
            val bytes = planHtml.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html;charset=UTF-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = AcademicHttpClient(baseUrl = "http://127.0.0.1:${server.address.port}/academic/")
            val jumpHtml = client.get("manager/studyschedule/studentSelfSchedule.jsdo", preferredCharset = CharsetDecoderHelper.GBK)
            val (encId, _) = AcademicParsers.parseCurriculumJumpParams(jumpHtml)
            val planHtml = client.get(AcademicRepository.buildCurriculumPlanUrl(encId), preferredCharset = CharsetDecoderHelper.GBK)
            val plan = AcademicParsers.parseCurriculumPlan(planHtml)

            assertEquals(rawPlusId, receivedQueryStudentId)
            assertEquals(1, plan.groups.size)
            assertEquals("学科基础课程（2023）", plan.groups[0].name)
            assertEquals("程序设计基础", plan.groups[0].courses[0].name)
        } finally {
            server.stop(0)
        }
    }
}
