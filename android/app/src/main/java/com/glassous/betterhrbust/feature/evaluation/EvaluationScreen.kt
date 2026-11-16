package com.glassous.betterhrbust.feature.evaluation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.ui.AppTextFieldShape
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.components.FixedPageTopBar
import com.glassous.betterhrbust.core.ui.components.pageTopBarTopPadding
import com.glassous.betterhrbust.core.ui.registerInputField
import com.glassous.betterhrbust.core.ui.theme.AppShapes
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
    // 仅在批处理进行中拦截返回以先停止任务；其余情况交给 NavHost 的原生预测性返回
    BackHandler(enabled = running) { stopped = true; message = "处理完当前课程后停止，再返回上一页" }
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
                bottom = LocalBottomContentInset.current + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = AppShapes.Card,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            "按你设置的评分与评语处理所选课程，每门课提交后核对学校返回的完成状态。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton(
                                onClick = { refresh() },
                                enabled = !loading && !running,
                                shape = AppShapes.Pill
                            ) { Text("刷新列表") }
                            FilledTonalButton(
                                enabled = chosen.isNotEmpty() && !loading && !running,
                                shape = AppShapes.Pill,
                                onClick = {
                                    loading = true; error = ""
                                    scope.launch {
                                        try { val form = repository.form(chosen.first()); template = form; ratings = form.questions.map { -1 }; comments = form.comments.map { "" } }
                                        catch (failure: CancellationException) { throw failure }
                                        catch (failure: Exception) { error = failure.message ?: "问卷读取失败" }
                                        finally { loading = false }
                                    }
                                }
                            ) { Text("读取问卷") }
                        }
                        if (loading) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                        if (message.isNotBlank()) {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (error.isNotBlank()) {
                            Text(
                                error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (!loading && tasks.isEmpty()) {
                            Text(
                                "学校当前未返回评价记录，请确认评价是否开放。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            items(tasks, key = { it.key }) { task ->
                val checked = task.key in selected
                Card(
                    Modifier.fillMaxWidth(),
                    shape = AppShapes.InnerCard,
                    colors = CardDefaults.cardColors(
                        containerColor = if (checked) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, enabled = !running && !loading && task.pending && task.url.isNotBlank(),
                            onCheckedChange = { isChecked -> selected = if (isChecked) selected + task.key else selected - task.key })
                        Column(Modifier.padding(start = 8.dp, top = 10.dp, bottom = 10.dp)) {
                            Text(
                                "${task.course} · ${task.teacher}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                task.status,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            template?.let { form ->
                item {
                    Text(
                        "统一评价配置",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(form.questions.indices.toList()) { index ->
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = AppShapes.Card,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text(
                                form.questions[index].title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(10.dp))
                            form.questions[index].options.forEachIndexed { optionIndex, option ->
                                val optionSelected = ratings[index] == optionIndex
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(AppShapes.Pill)
                                        .padding(end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = optionSelected,
                                        enabled = !running && !loading,
                                        onClick = { ratings = ratings.toMutableList().also { it[index] = optionIndex } }
                                    )
                                    Text(
                                        option.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (optionSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.padding(vertical = 12.dp)
                                    )
                                }
                            }
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
                    Button(
                        enabled = !loading && !running && chosen.isNotEmpty(),
                        shape = AppShapes.Pill,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        onClick = {
                            try { form.body(ratings, comments); error = ""; confirming = true }
                            catch (failure: Exception) { error = failure.message ?: "请检查评价配置" }
                        }
                    ) { Text("检查配置并批量提交", fontWeight = FontWeight.SemiBold) }
                }
            }
            if (running) {
                item {
                    OutlinedButton(
                        onClick = { stopped = true },
                        shape = AppShapes.Pill,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("当前课程结束后停止") }
                }
            }
        }
        FixedPageTopBar(title = "教学评价助手", onBack = back)
    }
    if (confirming) template?.let { form ->
        AlertDialog(onDismissRequest = { confirming = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("确认批量教学评价") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("将向学校提交 ${chosen.size} 门课程的评价，使用以下配置：")
                Spacer(Modifier.height(8.dp))
                form.questions.forEachIndexed { index, question -> Text("${question.title}：${question.options.getOrNull(ratings[index])?.label.orEmpty()}", style = MaterialTheme.typography.bodyMedium) }
                form.comments.forEachIndexed { index, comment -> Text("${comment.title}：${comments[index]}", style = MaterialTheme.typography.bodyMedium) }
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
