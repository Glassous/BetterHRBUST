package com.glassous.betterhrbust.feature.resources

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.glassous.betterhrbust.core.ui.AppTextFieldShape
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.components.FixedPageTopBar
import com.glassous.betterhrbust.core.ui.components.pageTopBarTopPadding
import com.glassous.betterhrbust.core.ui.registerInputField
import org.json.JSONObject

private data class ResourceFile(val title: String, val url: String)
private data class ResourceItem(val id: String, val title: String, val category: String, val date: String, val url: String, val files: List<ResourceFile>)

@Composable
fun ResourcesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val catalog = remember { runCatching {
        JSONObject(context.assets.open("resources.json").bufferedReader().use { it.readText() })
    } }
    val items = remember(catalog) { catalog.getOrNull()?.getJSONArray("items")?.let { array ->
        (0 until array.length()).map { index -> val row = array.getJSONObject(index); val files = row.getJSONArray("attachments")
            ResourceItem(row.getString("id"), row.getString("title"), row.getString("category"), row.getString("date"), row.getString("url"),
                (0 until files.length()).map { file -> val attachment = files.getJSONObject(file); ResourceFile(attachment.getString("title"), attachment.getString("url")) })
        }
    }.orEmpty() }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val filtered = items.filter { (category.isBlank() || it.category == category) && it.title.contains(query.trim(), ignoreCase = true) }
    fun open(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme !in listOf("http", "https") || uri.host?.endsWith(".hrbust.edu.cn") != true) { error = "资料地址不属于学校网站"; return }
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { error = "无法打开浏览器：${it.message}" }
    }
    // 固定顶栏：标题与返回按钮不随内容滚动，列表滚起来后顶栏底色转为不透明
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = pageTopBarTopPadding(),
                bottom = LocalBottomContentInset.current + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("教务处公开资料目录，原文与附件在学校网站打开。")
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索标题：学生证、缓考、四六级…") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .registerInputField(),
                    shape = AppTextFieldShape
                )
                Text("${filtered.size} 条结果 · 索引更新 ${catalog.getOrNull()?.optString("updatedAt")?.take(10).orEmpty()}", style = MaterialTheme.typography.bodySmall)
                if (catalog.isFailure) Text("资料索引读取失败", color = MaterialTheme.colorScheme.error)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = category.isBlank(), onClick = { category = "" }, label = { Text("全部") }) }
                items(items.map { it.category }.distinct()) { name -> FilterChip(selected = category == name, onClick = { category = name }, label = { Text(name) }) }
            } }
            items(filtered, key = { it.category + it.id }) { row ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium)
                    Text("${row.category} · ${row.date}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { open(row.url) }) { Text("查看学校原文") }
                    row.files.forEach { file -> TextButton(onClick = { open(file.url) }) { Text("↓ ${file.title.ifBlank { "附件" }}") } }
                } }
            }
            if (filtered.isEmpty() && catalog.isSuccess) item { Text("未找到匹配资料，请尝试其他关键词或分类。") }
            item { TextButton(onClick = { open("http://jwzx.hrbust.edu.cn/homepage/info.do?columnId=344") }) { Text("打开教务处资料下载原站") } }
        }
        FixedPageTopBar(title = "资料查找", onBack = onBack)
    }
}
