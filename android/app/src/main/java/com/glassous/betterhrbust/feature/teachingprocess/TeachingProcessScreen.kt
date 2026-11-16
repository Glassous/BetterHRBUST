package com.glassous.betterhrbust.feature.teachingprocess

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.model.PatternGroup
import com.glassous.betterhrbust.core.model.PersonalInfo
import com.glassous.betterhrbust.core.model.ProcessClass
import com.glassous.betterhrbust.core.model.ProcessSemester
import com.glassous.betterhrbust.core.model.StageInsights
import com.glassous.betterhrbust.core.model.StageSegment
import com.glassous.betterhrbust.core.model.StageStatus
import com.glassous.betterhrbust.core.model.TeachingProcessData
import com.glassous.betterhrbust.core.ui.AppTextFieldShape
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.components.EmptyView
import com.glassous.betterhrbust.core.ui.components.FixedPageTopBar
import com.glassous.betterhrbust.core.ui.components.LoadingView
import com.glassous.betterhrbust.core.ui.components.SegmentedControl
import com.glassous.betterhrbust.core.ui.components.pageTopBarTopPadding
import com.glassous.betterhrbust.core.ui.registerInputField
import com.glassous.betterhrbust.core.ui.theme.AppShapes
import com.glassous.betterhrbust.core.ui.theme.LocalDarkTheme
import com.glassous.betterhrbust.core.util.ClassNameMatcher
import com.glassous.betterhrbust.core.util.TeachingProcessCalculator
import com.glassous.betterhrbust.core.util.TeachingProcessSymbols
import com.glassous.betterhrbust.data.repository.AccountSwitchedException
import com.glassous.betterhrbust.data.repository.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 主视觉里「我的班级 / 推测的班级」的统一载体。 */
private data class HeroClass(
    val item: ProcessClass,
    val campus: String,
    val guessed: Boolean
)

/**
 * 本科教学进程与阶段（对齐 Web 端 TeachingProcessView）：
 * 学期 / 校区切换、我的班级（含档案推测）、本周阶段与剩余周数、整学期色带与阶段明细、
 * 全部班级按进程分组对照、符号说明。数据是随包内置的教务处公开快照，离线可用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeachingProcessScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val app = remember { BetterHrbustApp.instance }
    var data by remember { mutableStateOf<TeachingProcessData?>(null) }
    var loading by remember { mutableStateOf(true) }

    // 200KB+ 快照的读取与解析放到 IO 线程，避免首次进入页面卡住首帧
    LaunchedEffect(Unit) {
        data = withContext(Dispatchers.IO) { app.teachingProcessRepository.load() }
        loading = false
    }

    Box(modifier = modifier.fillMaxSize()) {
        val loaded = data
        when {
            loading -> LoadingView(message = "正在读取教学进程…")
            loaded == null || loaded.semesters.isEmpty() -> EmptyView(
                title = "教学进程数据缺失",
                description = "安装包内未包含进程表快照，请更新应用后重试"
            )

            else -> TeachingProcessContent(loaded)
        }
        // 二级页面固定顶栏：标题与返回按钮不随内容滚动
        FixedPageTopBar(title = "教学进程", onBack = onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeachingProcessContent(data: TeachingProcessData) {
    val app = remember { BetterHrbustApp.instance }
    val academicRepo = remember { app.academicRepository }
    val prefsManager = remember { app.preferencesManager }
    val coroutineScope = rememberCoroutineScope()

    val prefs by prefsManager.preferencesFlow.collectAsState(initial = null)

    val semesters = data.semestersDesc
    val currentSemesterId = remember(semesters) {
        semesters.firstOrNull { TeachingProcessCalculator.weekIndexOn(it.weeks) != null }?.id
            ?: semesters.firstOrNull()?.id
            .orEmpty()
    }
    var semesterId by rememberSaveable { mutableStateOf(currentSemesterId) }
    val semester = semesters.firstOrNull { it.id == semesterId } ?: semesters.firstOrNull()
    var campusName by rememberSaveable(semester?.id) { mutableStateOf("") }

    val campuses = semester?.campuses.orEmpty()
    val campus = campuses.firstOrNull { it.name == campusName } ?: campuses.firstOrNull()
    val classes = campus?.classes.orEmpty()
    val weekCount = semester?.weekCount ?: 0
    val currentWeek = remember(semester) {
        semester?.let { TeachingProcessCalculator.weekIndexOn(it.weeks) } ?: 0
    }

    // 我的班级：本地记忆优先；未指定时用学籍档案推测（仅建议，确认后才落库）
    var myClassName by rememberSaveable { mutableStateOf("") }
    var myClassCampus by rememberSaveable { mutableStateOf("") }
    var prefsApplied by remember { mutableStateOf(false) }
    LaunchedEffect(prefs) {
        val loaded = prefs ?: return@LaunchedEffect
        if (prefsApplied) return@LaunchedEffect
        prefsApplied = true
        myClassName = loaded.teachingProcessClass
        myClassCampus = loaded.teachingProcessCampus
    }

    var profile by remember { mutableStateOf<PersonalInfo?>(null) }
    LaunchedEffect(prefs?.username) {
        val username = prefs?.username.orEmpty()
        if (username.isEmpty()) return@LaunchedEffect
        try {
            academicRepo.getPersonalInfo(username, cacheOnly = true).collect { res ->
                if (res is Resource.Success) profile = res.data
            }
        } catch (_: AccountSwitchedException) {
            // 账号已切换：推测依据作废，保持未指定状态
        }
    }

    // 用户主动清除班级后不再反复弹出档案推测
    var guessDismissed by rememberSaveable { mutableStateOf(false) }

    val myEntry = remember(myClassName, campuses) {
        if (myClassName.isEmpty()) {
            null
        } else {
            campuses.firstNotNullOfOrNull { item ->
                item.classes.firstOrNull { it.name == myClassName }?.let { HeroClass(it, item.name, false) }
            }
        }
    }
    val guess = remember(profile, campuses, myClassName, guessDismissed) {
        val current = profile
        if (current == null || myClassName.isNotEmpty() || guessDismissed) {
            null
        } else if (current.className.isBlank() && current.major.isBlank()) {
            null
        } else {
            ClassNameMatcher.pickBestClass(
                current,
                campuses.flatMap { item -> item.classes.map { it.name to item.name } }
            )?.takeIf { it.confident }
        }
    }
    val guessEntry = remember(guess, campuses) {
        guess?.let { match ->
            campuses.firstNotNullOfOrNull { item ->
                item.classes.firstOrNull { it.name == match.name }?.let { HeroClass(it, item.name, true) }
            }
        }
    }
    val hero = myEntry ?: guessEntry

    val heroSegments = remember(hero, data.patterns) {
        hero?.let {
            TeachingProcessCalculator.buildSegments(
                TeachingProcessCalculator.decodePattern(data.patterns.getOrNull(it.item.patternIndex))
            )
        }.orEmpty()
    }
    val insights = remember(heroSegments, currentWeek, weekCount) {
        TeachingProcessCalculator.stageInsights(heroSegments, currentWeek, weekCount)
    }
    val heroActiveParts = remember(hero, currentWeek, data.patterns) {
        if (hero == null || currentWeek <= 0) {
            emptyList()
        } else {
            val cells = TeachingProcessCalculator.decodePattern(data.patterns.getOrNull(hero.item.patternIndex))
            TeachingProcessSymbols.cellParts(cells.getOrNull(currentWeek - 1))
        }
    }

    // 全部班级：默认收起，需要横向比较时再展开
    var allOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var groupView by rememberSaveable { mutableStateOf(0) } // 0 = 按进程，1 = 按班级
    var expandedGroup by rememberSaveable { mutableStateOf(-1) }
    var legendOpen by rememberSaveable { mutableStateOf(false) }

    val normalizedQuery = query.trim().lowercase()
    val filteredClasses = remember(classes, normalizedQuery) {
        if (normalizedQuery.isEmpty()) classes else classes.filter { it.name.lowercase().contains(normalizedQuery) }
    }
    val groups = remember(filteredClasses, myClassName, data.patterns) {
        TeachingProcessCalculator.groupByPattern(filteredClasses, data.patterns, myClassName)
    }

    // 选班弹窗
    var pickerOpen by remember { mutableStateOf(false) }
    var pickerQuery by remember { mutableStateOf("") }
    var pickerCampusName by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun setMyClass(name: String, campusValue: String) {
        myClassName = name
        myClassCampus = campusValue
        coroutineScope.launch { prefsManager.setTeachingProcessClass(name, campusValue) }
    }

    fun clearMyClass() {
        myClassName = ""
        myClassCampus = ""
        guessDismissed = true
        coroutineScope.launch { prefsManager.setTeachingProcessClass("", "") }
    }

    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = pageTopBarTopPadding(),
            bottom = LocalBottomContentInset.current + 16.dp
        )
    ) {
        // ---------------- 学期 / 校区 ----------------
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = AppShapes.Card,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "本科教学进程",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "教务处制 · 按学期、校区与阶段重新整理",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    SemesterSelector(
                        semesters = semesters,
                        semester = semester,
                        isCurrentSemester = semester?.id == currentSemesterId,
                        onSelect = { semesterId = it },
                        onBackToCurrent = { semesterId = currentSemesterId }
                    )

                    if (campuses.size > 1) {
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(campuses, key = { it.name }) { item ->
                                FilterChip(
                                    selected = item.name == campus?.name,
                                    onClick = { campusName = item.name },
                                    shape = AppShapes.Pill,
                                    label = { Text(item.name) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    val span = remember(semester) { semesterSpan(semester) }
                    Text(
                        text = "$span · $weekCount 周 · ${campuses.size} 校区 / ${campuses.sumOf { it.classes.size }} 个班",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "原站更新 ${data.sourceUpdatedAt.ifBlank { "未标注" }} · 本页整理 ${data.updatedAt.ifBlank { "未标注" }}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---------------- 主视觉：我这个班现在处于什么阶段 ----------------
        item {
            when {
                hero != null -> HeroCard(
                    hero = hero,
                    heroSegments = heroSegments,
                    insights = insights,
                    activeParts = heroActiveParts.map { it.label },
                    currentWeek = currentWeek,
                    weekCount = weekCount,
                    semester = semester,
                    onConfirmGuess = { setMyClass(hero.item.name, hero.campus) },
                    onOpenPicker = {
                        pickerQuery = ""
                        pickerCampusName = myClassCampus.ifEmpty { campus?.name.orEmpty() }
                        pickerOpen = true
                    }
                )

                myClassName.isNotEmpty() -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.Hero,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("我的班级", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "本学期的进程表里没有「$myClassName」，可能该学期未收录这个班级，或班级已经调整。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                pickerQuery = ""
                                pickerCampusName = myClassCampus.ifEmpty { campus?.name.orEmpty() }
                                pickerOpen = true
                            }, shape = AppShapes.Pill) { Text("换一个班级") }
                            TextButton(onClick = { clearMyClass() }) { Text("清除") }
                        }
                    }
                }

                else -> CampusStageCard(
                    campusName = campus?.name.orEmpty(),
                    semesterLabel = semester?.label.orEmpty(),
                    currentWeek = currentWeek,
                    weekCount = weekCount,
                    classes = classes,
                    patterns = data.patterns,
                    onOpenPicker = {
                        pickerQuery = ""
                        pickerCampusName = campus?.name.orEmpty()
                        pickerOpen = true
                    }
                )
            }
        }

        // ---------------- 全部班级 ----------------
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = AppShapes.Card,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { allOpen = !allOpen }
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("全部班级的进程", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${groups.size} 种进程 · ${classes.size} 个班 · 按进度分组或逐班对照",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = if (allOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AnimatedVisibility(visible = allOpen) {
                        Column(
                            modifier = Modifier.padding(
                                start = 20.dp,
                                end = 20.dp,
                                top = 2.dp,
                                bottom = 20.dp
                            )
                        ) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                label = { Text("搜索班级，例如：材型、软件、电气") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .registerInputField(),
                                shape = AppTextFieldShape
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            SegmentedOptions(
                                options = listOf("按进程", "按班级"),
                                selected = groupView,
                                onSelect = { groupView = it }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${groups.size} 组 · ${filteredClasses.size} 个班",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        if (allOpen) {
            if (filteredClasses.isEmpty()) {
                item {
                    Surface(
                        shape = AppShapes.InnerCard,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "没有匹配的班级，试试年级数字（如 26）或专业缩写（如 材型）。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                        )
                    }
                }
            } else if (groupView == 0) {
                items(groups, key = { "group-${it.patternIndex}" }) { group ->
                    PatternGroupCard(
                        group = group,
                        weekCount = weekCount,
                        currentWeek = currentWeek,
                        myClassName = myClassName,
                        expanded = expandedGroup == group.patternIndex,
                        onToggle = {
                            expandedGroup = if (expandedGroup == group.patternIndex) -1 else group.patternIndex
                        }
                    )
                }
            } else {
                items(filteredClasses, key = { "class-${it.name}" }) { item ->
                    ClassRow(
                        item = item,
                        patterns = data.patterns,
                        weekCount = weekCount,
                        currentWeek = currentWeek,
                        isMine = item.name == myClassName
                    )
                }
            }
        }

        // ---------------- 符号与阶段 ----------------
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = AppShapes.Card,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { legendOpen = !legendOpen }
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("符号与阶段", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "原表用单个字形表示整周安排，这里按含义分组并配色",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = if (legendOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AnimatedVisibility(visible = legendOpen) {
                        Column(
                            modifier = Modifier.padding(
                                start = 20.dp,
                                end = 20.dp,
                                bottom = 20.dp
                            )
                        ) {
                            LegendGroups()
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "原表只标注军训、实习、考试、假期等特殊安排，其余周次留空并按「常规教学」显示，表示按课表正常上课；" +
                                    "未收录字形（${TeachingProcessSymbols.undocumented.joinToString(" ")}）以中性虚线提示，含义以原表图例为准。",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val legend = semester?.legend.orEmpty()
                            if (legend.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "原表图例",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                legend.forEach { line ->
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            val warnings = data.warnings
                            if (warnings.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "整理时发现 ${warnings.size} 处对不上的地方：",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                warnings.take(5).forEach { warning ->
                                    Text(
                                        text = "· $warning",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (pickerOpen) {
        ModalBottomSheet(
            onDismissRequest = { pickerOpen = false },
            sheetState = sheetState,
            shape = AppShapes.Sheet,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            ClassPickerSheet(
                campuses = campuses,
                initialCampus = pickerCampusName,
                query = pickerQuery,
                onQueryChange = { pickerQuery = it },
                onCampusChange = { pickerCampusName = it },
                onPick = { name, campusValue ->
                    setMyClass(name, campusValue)
                    pickerOpen = false
                },
                onClear = {
                    clearMyClass()
                    pickerOpen = false
                }
            )
        }
    }
}

/* ---------------- 学期选择 ---------------- */

@Composable
private fun SemesterSelector(
    semesters: List<ProcessSemester>,
    semester: ProcessSemester?,
    isCurrentSemester: Boolean,
    onSelect: (String) -> Unit,
    onBackToCurrent: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val index = semesters.indexOfFirst { it.id == semester?.id }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { semesters.getOrNull(index + 1)?.let { onSelect(it.id) } },
            enabled = index in 0 until semesters.lastIndex
        ) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "更早的学期")
        }
        Box {
            Row(
                modifier = Modifier
                    .clip(AppShapes.Pill)
                    .clickable { menuOpen = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = semester?.label.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = "选择学期",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                semesters.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.label) },
                        onClick = {
                            onSelect(item.id)
                            menuOpen = false
                        }
                    )
                }
            }
        }
        IconButton(
            onClick = { semesters.getOrNull(index - 1)?.let { onSelect(it.id) } },
            enabled = index > 0
        ) {
            Icon(Icons.Default.ChevronRight, contentDescription = "更新的学期")
        }
        if (!isCurrentSemester) {
            TextButton(onClick = onBackToCurrent) { Text("回到本学期") }
        }
    }
}

private fun semesterSpan(semester: ProcessSemester?): String {
    val weeks = semester?.weeks.orEmpty()
    if (weeks.isEmpty()) return "—"
    return "${TeachingProcessCalculator.shortDate(weeks.first().monday)} – " +
        TeachingProcessCalculator.shortDate(weeks.last().sunday)
}

/* ---------------- 主视觉卡片 ---------------- */

@Composable
private fun HeroCard(
    hero: HeroClass,
    heroSegments: List<StageSegment>,
    insights: StageInsights,
    activeParts: List<String>,
    currentWeek: Int,
    weekCount: Int,
    semester: ProcessSemester?,
    onConfirmGuess: () -> Unit,
    onOpenPicker: () -> Unit
) {
    val title = when {
        currentWeek <= 0 -> "${semester?.label.orEmpty()} 已结束"
        activeParts.isNotEmpty() -> activeParts.joinToString(" / ")
        else -> "常规教学"
    }
    val detail = when {
        currentWeek <= 0 -> "该学期进程已结束，下面是完整阶段安排。"
        insights.current == null -> "这一周原表没有标注安排。"
        insights.current.group == TeachingProcessSymbols.IDLE_KEY ->
            "第 ${insights.current.startWeek}–${insights.current.endWeek} 周 · 共 ${insights.current.weekCount.toInt().coerceAtLeast(1)} 周 · 按课表正常上课"
        else ->
            "第 ${insights.current.startWeek}–${insights.current.endWeek} 周 · 共 ${insights.current.weekCount.toInt().coerceAtLeast(1)} 周 · 本周第 ${insights.currentElapsed} 周"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Hero,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column {
            if (hero.guessed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "根据学籍档案推测你是 ${hero.item.name}（${hero.campus}）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onConfirmGuess,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) { Text("是我的班级") }
                }
            }

            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = hero.item.name,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = AppShapes.Pill,
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = hero.campus,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "周学时 ${if (hero.item.hours > 0) hero.item.hours.toString() else "—"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onOpenPicker) { Text("换一个") }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (currentWeek > 0) {
                        Surface(
                            shape = AppShapes.Pill,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "第 $currentWeek / $weekCount 周",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(
                        text = semester?.label.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (currentWeek > 0) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile(
                            label = if (insights.current?.key == "vacation") "假期还剩" else "本阶段还剩",
                            value = if (insights.currentRemaining > 0) "${insights.currentRemaining} 周" else "—",
                            hint = insights.current?.let { "到第 ${it.endWeek} 周" }.orEmpty(),
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "下一阶段",
                            value = insights.next?.short ?: "已无",
                            hint = insights.next?.let { "${insights.nextIn} 周后 · 第 ${it.startWeek} 周" }
                                ?: "本学期最后一个阶段",
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "距离放假",
                            value = insights.vacation?.let {
                                if (insights.vacationIn > 0) "${insights.vacationIn} 周后" else "假期中"
                            } ?: "未标注",
                            hint = insights.vacation?.let { "第 ${it.startWeek} 周起" } ?: "原表未标注假期",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                StageTimeline(
                    segments = heroSegments,
                    weekCount = weekCount,
                    currentWeek = currentWeek,
                    showDates = true,
                    firstDate = semester?.weeks?.firstOrNull()?.let {
                        TeachingProcessCalculator.shortDate(it.monday)
                    }.orEmpty(),
                    lastDate = semester?.weeks?.lastOrNull()?.let {
                        TeachingProcessCalculator.shortDate(it.sunday)
                    }.orEmpty()
                )

                if (heroSegments.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "我这学期的阶段",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "${heroSegments.size} 段 · 共 $weekCount 周",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    StageList(
                        segments = heroSegments,
                        weeks = semester?.weeks.orEmpty(),
                        currentWeek = currentWeek
                    )
                }
            }
        }
    }
}

@Composable
private fun CampusStageCard(
    campusName: String,
    semesterLabel: String,
    currentWeek: Int,
    weekCount: Int,
    classes: List<ProcessClass>,
    patterns: List<String>,
    onOpenPicker: () -> Unit
) {
    val snapshot = remember(classes, patterns, currentWeek) {
        if (currentWeek <= 0) emptyList() else TeachingProcessCalculator.snapshotAt(classes, patterns, currentWeek)
    }
    val top = snapshot.firstOrNull { it.group != TeachingProcessSymbols.IDLE_KEY } ?: snapshot.firstOrNull()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Hero,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("现在处于什么阶段？", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            if (currentWeek > 0) {
                Text(
                    text = "第 $currentWeek / $weekCount 周 · $campusName · $semesterLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            Text(
                text = if (top != null && currentWeek > 0) top.label else "本学期还未开始",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (top != null && currentWeek > 0) {
                    "$campusName 有 ${top.count} 个班处于这个安排。指定你的班级，就能看到自己的军训、实习、考试与放假时间。"
                } else {
                    "指定你的班级后，这里会显示你所在班级的教学阶段。数据来自教务处公开的本科教学进程表，不需要登录。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (snapshot.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(snapshot.take(5), key = { it.key }) { entry ->
                        Row(
                            modifier = Modifier
                                .clip(AppShapes.Pill)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StageDot(group = entry.group, key = entry.key, size = 8.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${entry.label} ${entry.count}",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onOpenPicker, shape = AppShapes.Pill) { Text("指定我的班级") }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    hint: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = AppShapes.InnerCard,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (hint.isNotEmpty()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/* ---------------- 色带与阶段列表 ---------------- */

/**
 * 整学期色带：阶段宽度按周数比例，斜杠周的前后半周也能对上；
 * 周分隔线与当前周高亮叠加在色块之上。
 */
@Composable
private fun StageTimeline(
    segments: List<StageSegment>,
    weekCount: Int,
    currentWeek: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showDates: Boolean = false,
    firstDate: String = "",
    lastDate: String = ""
) {
    val dark = LocalDarkTheme.current
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val accent = MaterialTheme.colorScheme.primary
    val colors = remember(segments, dark) {
        segments.map { stageColor(it.group, it.key, dark) }
    }
    val height = if (compact) 10.dp else 22.dp

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(AppShapes.Pill)
                .background(track)
        ) {
            if (weekCount <= 0) return@Canvas
            val width = size.width
            segments.forEachIndexed { index, segment ->
                val left = segment.startValue / weekCount * width
                val right = segment.endValue / weekCount * width
                drawRect(
                    color = colors[index],
                    topLeft = Offset(left, 0f),
                    size = Size((right - left).coerceAtLeast(1.5.dp.toPx()), size.height)
                )
            }
            if (!compact) {
                for (week in 1 until weekCount) {
                    val x = width / weekCount * week
                    drawLine(divider, Offset(x, 0f), Offset(x, size.height), 0.5.dp.toPx())
                }
            }
            if (currentWeek in 1..weekCount) {
                val left = width / weekCount * (currentWeek - 1)
                drawRect(
                    color = accent.copy(alpha = 0.18f),
                    topLeft = Offset(left, 0f),
                    size = Size(width / weekCount, size.height)
                )
                drawLine(accent, Offset(left, 0f), Offset(left, size.height), 2.dp.toPx())
            }
        }
        if (showDates) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = if (firstDate.isNotEmpty()) "第 1 周 $firstDate" else "第 1 周",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (currentWeek > 0) {
                    Text(
                        text = "当前第 $currentWeek / $weekCount 周",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accent
                    )
                }
                Text(
                    text = if (lastDate.isNotEmpty()) "第 $weekCount 周 $lastDate" else "第 $weekCount 周",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StageList(
    segments: List<StageSegment>,
    weeks: List<com.glassous.betterhrbust.core.model.ProcessWeek>,
    currentWeek: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEach { segment ->
            StageRow(segment = segment, weeks = weeks, currentWeek = currentWeek)
        }
    }
}

@Composable
private fun StageRow(
    segment: StageSegment,
    weeks: List<com.glassous.betterhrbust.core.model.ProcessWeek>,
    currentWeek: Int
) {
    val status = TeachingProcessCalculator.stageStatusOf(segment, currentWeek)
    val dark = LocalDarkTheme.current
    val color = stageColor(segment.group, segment.key, dark)
    val container = if (status == StageStatus.CURRENT) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.InnerCard)
            .background(container)
            .height(IntrinsicSize.Min)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = segment.short,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (status == StageStatus.CURRENT) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (status == StageStatus.PAST) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Spacer(modifier = Modifier.width(6.dp))
                when (status) {
                    StageStatus.CURRENT -> Text(
                        text = "进行中",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    StageStatus.PAST -> Text(
                        text = "已结束",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    StageStatus.UPCOMING -> if (currentWeek > 0) {
                        Text(
                            text = "${segment.startWeek - currentWeek} 周后开始",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    StageStatus.UNKNOWN -> Unit
                }
            }
            val range = TeachingProcessCalculator.formatWeekRange(segment)
            val dates = TeachingProcessCalculator.dateRange(segment, weeks)
            val weekText = TeachingProcessCalculator.weekText(segment)
            Text(
                text = listOfNotNull(
                    range,
                    dates.ifEmpty { null },
                    weekText.ifEmpty { null },
                    segment.note.ifEmpty { null }
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StageDot(group: String, key: String, size: androidx.compose.ui.unit.Dp) {
    val dark = LocalDarkTheme.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(stageColor(group, key, dark))
    )
}

/** 阶段配色：分组色相 + 细分色相；中性组（假期 / 常规教学 / 未收录）单独处理。 */
private fun stageColor(group: String, key: String, dark: Boolean): Color {
    if (group == TeachingProcessSymbols.UNKNOWN_KEY) {
        return if (dark) Color(0xFFCF6679) else Color(0xFFB3261E)
    }
    val hue = TeachingProcessSymbols.hueOf(group, key)
    if (hue == null) {
        return when (group) {
            "break" -> if (dark) Color(0xFF9AA0A6) else Color(0xFF8A8F98)
            TeachingProcessSymbols.IDLE_KEY -> if (dark) Color(0xFF3C4043) else Color(0xFFD7DAE0)
            else -> if (dark) Color(0xFF9AA0A6) else Color(0xFF8A8F98)
        }
    }
    return Color.hsl(hue.toFloat(), 0.55f, if (dark) 0.62f else 0.48f)
}

/* ---------------- 全部班级 ---------------- */

@Composable
private fun PatternGroupCard(
    group: PatternGroup,
    weekCount: Int,
    currentWeek: Int,
    myClassName: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                StageTimeline(
                    segments = group.segments,
                    weekCount = weekCount,
                    currentWeek = currentWeek,
                    compact = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = group.summary,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${group.classes.size} 个班",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                    StageList(segments = group.segments, weeks = emptyList(), currentWeek = currentWeek)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "同进度的班级",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    group.classes.chunked(3).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowItems.forEach { item ->
                                val mine = item.name == myClassName && myClassName.isNotEmpty()
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = AppShapes.Pill,
                                    color = if (mine) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    }
                                ) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (mine) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (mine) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ClassRow(
    item: ProcessClass,
    patterns: List<String>,
    weekCount: Int,
    currentWeek: Int,
    isMine: Boolean
) {
    val segments = remember(item, patterns) {
        TeachingProcessCalculator.buildSegments(
            TeachingProcessCalculator.decodePattern(patterns.getOrNull(item.patternIndex))
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.InnerCard,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isMine) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (item.hours > 0) "${item.hours} 学时" else "— 学时",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            StageTimeline(
                segments = segments,
                weekCount = weekCount,
                currentWeek = currentWeek,
                compact = true
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = TeachingProcessCalculator.summaryOf(segments),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/* ---------------- 图例 ---------------- */

@Composable
private fun LegendGroups() {
    val dark = LocalDarkTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TeachingProcessSymbols.legendGroups().forEach { group ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StageDot(group = group.group, key = "", size = 10.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = group.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                group.symbols.chunked(2).forEach { rowSymbols ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowSymbols.forEach { symbol ->
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(AppShapes.Pill)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(stageColor(group.group, symbol.key, dark))
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = symbol.glyph,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = symbol.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

/* ---------------- 选班弹窗 ---------------- */

@Composable
private fun ClassPickerSheet(
    campuses: List<com.glassous.betterhrbust.core.model.ProcessCampus>,
    initialCampus: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onCampusChange: (String) -> Unit,
    onPick: (String, String) -> Unit,
    onClear: () -> Unit
) {
    var pickerCampus by remember { mutableStateOf(initialCampus) }
    val target = campuses.firstOrNull { it.name == pickerCampus } ?: campuses.firstOrNull()
    val keyword = query.trim().lowercase()
    val results = remember(target, keyword) {
        val list = target?.classes.orEmpty()
        (if (keyword.isEmpty()) list else list.filter { it.name.lowercase().contains(keyword) }).take(80)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp)
    ) {
        Text("指定我的班级", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            label = { Text("搜索班级名，例如：材型、软件、电气") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .registerInputField(),
            shape = AppTextFieldShape
        )
        if (campuses.size > 1) {
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(campuses, key = { it.name }) { item ->
                    FilterChip(
                        selected = item.name == target?.name,
                        onClick = {
                            pickerCampus = item.name
                            onCampusChange(item.name)
                        },
                        shape = AppShapes.Pill,
                        label = { Text(item.name) }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
            items(results, key = { it.name }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(AppShapes.InnerCard)
                        .clickable { onPick(item.name, target?.name.orEmpty()) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (item.hours > 0) "${item.hours} 学时" else "— 学时",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (results.isEmpty()) {
                item {
                    Text(
                        text = "没有匹配的班级",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 14.dp)
                    )
                }
            }
        }
        TextButton(onClick = onClear) { Text("清除我的班级") }
    }
}

/* ---------------- 轻量分段切换 ---------------- */

@Composable
private fun SegmentedOptions(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    SegmentedControl(
        options = options.mapIndexed { index, label -> index to label },
        selected = selected,
        onSelect = onSelect
    )
}
