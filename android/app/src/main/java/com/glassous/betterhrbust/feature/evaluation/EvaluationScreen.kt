package com.glassous.betterhrbust.feature.evaluation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.ui.AppTextFieldShape
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.components.FixedPageTopBar
import com.glassous.betterhrbust.core.ui.components.pageTopBarTopPadding
import com.glassous.betterhrbust.core.ui.registerInputField
import com.glassous.betterhrbust.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun EvaluationScreen(onBack: () -> Unit) {
    val repository = remember { EvaluationRepository(BetterHrbustApp.instance.httpClient) }
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf(emptyList<EvaluationTask>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var template by remember { mutableStateOf<EvaluationForm?>(null) }
    var ratings by remember { mutableStateOf(emptyList<Int>()) }
    var comments by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var stopped by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val chosen = tasks.filter { it.key in selected && it.pending && it.url.isNotBlank() }
    val back = { if (running) { stopped = true; message = "处理完当前课程后停止，再返回上一页" } else onBack() }
    BackHandler(onBack = back)
    DisposableEffect(Unit) { onDispose { stopped = true } }
    fun refresh() {
        if (loading || running) return
        loading = true; error = ""
        scope.launch {
            try { tasks = repository.tasks(); selected = tasks.filter { it.pending && it.url.isNotBlank() }.map { it.key }.toSet() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { error = failure.message ?: "评价列表读取失败" }
            finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    // 固定顶栏：标题与返回按钮不随内容滚动，列表滚起来后顶栏底色转为不透明
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = pageTopBarTopPadding(),
                bottom = LocalBottomContentInset.current + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("按你设置的评分与评语处理所选课程，每门课提交后核对学校返回的完成状态。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { refresh() }, enabled = !loading && !running) { Text("刷新列表") }
                    OutlinedButton(enabled = chosen.isNotEmpty() && !loading && !running, onClick = {
                        loading = true; error = ""
                        scope.launch {
                            try { val form = repository.form(chosen.first()); template = form; ratings = form.questions.map { -1 }; comments = form.comments.map { "" } }
                            catch (failure: CancellationException) { throw failure }
                            catch (failure: Exception) { error = failure.message ?: "问卷读取失败" }
                            finally { loading = false }
                        }
                    }) { Text("读取问卷") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (!loading && tasks.isEmpty()) Text("学校当前未返回评价记录，请确认评价是否开放。")
            }
            items(tasks, key = { it.key }) { task ->
                Row {
                    Checkbox(checked = task.key in selected, enabled = !running && !loading && task.pending && task.url.isNotBlank(),
                        onCheckedChange = { checked -> selected = if (checked) selected + task.key else selected - task.key })
                    Column(Modifier.padding(top = 8.dp)) { Text("${task.course} · ${task.teacher}"); Text(task.status, style = MaterialTheme.typography.bodySmall) }
                }
            }
            template?.let { form ->
                item { Text("统一评价配置", style = MaterialTheme.typography.titleLarge) }
                items(form.questions.indices.toList()) { index ->
                    Column { Text(form.questions[index].title)
                        form.questions[index].options.forEachIndexed { optionIndex, option ->
                            Row { RadioButton(selected = ratings[index] == optionIndex, enabled = !running && !loading,
                                onClick = { ratings = ratings.toMutableList().also { it[index] = optionIndex } }); Text(option.label, Modifier.padding(top = 13.dp)) }
                        }
                    }
                }
                items(form.comments.indices.toList()) { index ->
                    OutlinedTextField(value = comments[index], onValueChange = { value -> comments = comments.toMutableList().also { it[index] = value } },
                        label = { Text(form.comments[index].title) }, enabled = !running && !loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .registerInputField(), shape = AppTextFieldShape)
                }
                item {
                    Button(enabled = !loading && !running && chosen.isNotEmpty(), onClick = {
                        try { form.body(ratings, comments); error = ""; confirming = true }
                        catch (failure: Exception) { error = failure.message ?: "请检查评价配置" }
                    }) { Text("检查配置并批量提交") }
                }
            }
            if (running) item { OutlinedButton(onClick = { stopped = true }) { Text("当前课程结束后停止") } }
        }
        FixedPageTopBar(title = "教学评价助手", onBack = back)
    }
    if (confirming) template?.let { form ->
        AlertDialog(onDismissRequest = { confirming = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("确认批量教学评价") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("将向学校提交 ${chosen.size} 门课程的评价，使用以下配置：")
                form.questions.forEachIndexed { index, question -> Text("${question.title}：${question.options.getOrNull(ratings[index])?.label.orEmpty()}") }
                form.comments.forEachIndexed { index, comment -> Text("${comment.title}：${comments[index]}") }
            } }, dismissButton = { TextButton(onClick = { confirming = false }) { Text("返回修改") } },
            confirmButton = { TextButton(enabled = !running && !loading, onClick = {
                if (running || loading) return@TextButton
                val batch = chosen.toList(); val choices = ratings.toList(); val text = comments.toList()
                confirming = false; running = true; stopped = false; error = ""
                scope.launch {
                    try {
                        val count = repository.submit(batch, form, choices, text, { stopped }, { message = it })
                        message = "已核对完成 $count 门课程。${if (stopped) "其余课程已停止。" else ""}"
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { error = failure.message ?: "提交未确认，请刷新列表核对，勿重复提交" }
                    finally { running = false }
                    try { tasks = repository.tasks(); selected = tasks.filter { it.pending && it.url.isNotBlank() }.map { it.key }.toSet() }
                    catch (failure: CancellationException) { throw failure }
                    catch (_: Exception) { /* 保留原提交结果，用户可手动刷新。 */ }
                }
            }) { Text("确认使用以上配置提交") } })
    }
}
