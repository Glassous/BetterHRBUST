package com.glassous.betterhrbust.feature.courses

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.model.CourseSearchItem
import com.glassous.betterhrbust.core.ui.AppTextFieldShape
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.components.EmptyView
import com.glassous.betterhrbust.core.ui.components.FixedPageTopBar
import com.glassous.betterhrbust.core.ui.components.LoadingView
import com.glassous.betterhrbust.core.ui.components.pageTopBarTopPadding
import com.glassous.betterhrbust.core.ui.registerInputField
import com.glassous.betterhrbust.data.repository.Resource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoursesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val app = remember { BetterHrbustApp.instance }
    val academicRepo = remember { app.academicRepository }
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<CourseSearchItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // 在途检索任务：新输入先取消旧任务，慢旧响应不会覆盖新结果
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun doSearch(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.length < 2) return
        searchJob?.cancel()
        searchJob = coroutineScope.launch {
            // 防抖：连续键入时只在停顿 300ms 后真正发请求，
            // 避免每键入一字打一次教务接口
            delay(300)
            isLoading = true
            try {
                academicRepo.searchCourses(trimmed).collect { res ->
                    if (res is Resource.Success) {
                        searchResults = res.data
                    }
                    isLoading = false
                }
            } finally {
                isLoading = false
            }
        }
    }

    // 整页为单一滚动列表，标题改为固定顶栏（滚动后底色转为不透明，内容从顶栏下方滚过）
    val listState = rememberLazyListState()
    Box(modifier = modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(
            top = pageTopBarTopPadding(),
            bottom = LocalBottomContentInset.current + 24.dp
        )
    ) {
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    doSearch(it)
                },
                placeholder = { Text("输入课程名、课号或教师检索...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .registerInputField(),
                shape = AppTextFieldShape
            )
        }

        if (isLoading) {
            item {
                LoadingView(
                    message = "正在检索课程目录...",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                )
            }
        } else if (searchResults.isEmpty()) {
            item {
                EmptyView(
                    title = "未检索到课程",
                    description = "请在上方输入课程关键词或代码查询",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                )
            }
        } else {
            items(searchResults) { course ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = course.courseName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "${course.credit} 学分",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${course.courseId} · ${course.department.ifEmpty { "哈尔滨理工大学" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (course.hours > 0 || course.examWay.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${course.hours} 学时 · ${course.examWay.ifEmpty { "考试" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
        FixedPageTopBar(title = "我的课程名录", onBack = onBack)
    }
}
