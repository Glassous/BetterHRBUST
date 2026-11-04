package com.glassous.betterhrbust.feature.classrooms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.model.ClassroomDaySchedule
import com.glassous.betterhrbust.core.model.ClassroomRoom
import com.glassous.betterhrbust.core.model.NamedOption
import com.glassous.betterhrbust.core.parser.ClassroomFreeRange
import com.glassous.betterhrbust.core.parser.ClassroomScheduleLogic
import com.glassous.betterhrbust.core.ui.AppTextFieldShape
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.components.EmptyView
import com.glassous.betterhrbust.core.ui.components.ErrorView
import com.glassous.betterhrbust.core.ui.components.FixedPageTopBar
import com.glassous.betterhrbust.core.ui.components.LoadingView
import com.glassous.betterhrbust.core.ui.components.pageTopBarTopPadding
import com.glassous.betterhrbust.core.ui.registerInputField
import com.glassous.betterhrbust.core.ui.theme.LocalDarkTheme
import com.glassous.betterhrbust.data.repository.Resource
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 教室 + 空闲时段汇总（UI 层模型） */
private data class RoomSummary(
    val room: ClassroomRoom,
    val ranges: List<ClassroomFreeRange>,
    val labels: List<String>,
    val occupiedCount: Int,
    val freeSpan: Int
)

private val WEEKDAY_TEXTS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private const val ROOM_COLUMN_WIDTH = 108
private const val SECTION_CELL_SIZE = 30

@Composable
fun ClassroomsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val app = remember { BetterHrbustApp.instance }
    val academicRepo = remember { app.academicRepository }
    val prefsManager = remember { app.preferencesManager }
    val prefs by prefsManager.preferencesFlow.collectAsState(initial = null)
    val coroutineScope = rememberCoroutineScope()
    val isDark = LocalDarkTheme.current

    var areas by remember { mutableStateOf<List<NamedOption>>(emptyList()) }
    var buildings by remember { mutableStateOf<List<NamedOption>>(emptyList()) }
    var weeks by remember { mutableStateOf<List<Int>>(emptyList()) }
    var days by remember { mutableStateOf<List<Int>>(emptyList()) }

    var selectedArea by remember { mutableStateOf("") }
    var selectedBuilding by remember { mutableStateOf("") }
    var selectedWeek by remember { mutableIntStateOf(0) }
    // LocalDate.dayOfWeek: 周一=1 … 周日=7，与教务 week 参数一致
    var selectedDay by remember { mutableIntStateOf(LocalDate.now().dayOfWeek.value) }

    var schedule by remember { mutableStateOf<ClassroomDaySchedule?>(null) }
    val daySchedules = remember { mutableStateMapOf<Int, ClassroomDaySchedule>() }
    var weekLoaded by remember { mutableStateOf(false) }
    var dataVersion by remember { mutableIntStateOf(0) }

    var isLoadingOptions by remember { mutableStateOf(true) }
    var isLoadingSchedule by remember { mutableStateOf(false) }
    var isWeekLoading by remember { mutableStateOf(false) }
    var weekProgress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }

    var searchQuery by remember { mutableStateOf("") }
    var onlyFree by remember { mutableStateOf(false) }
    var minSpan by remember { mutableIntStateOf(2) }
    var visibleLimit by remember { mutableIntStateOf(40) }

    fun resetDayCache() {
        daySchedules.clear()
        weekLoaded = false
        weekProgress = 0
        dataVersion++
    }

    suspend fun queryDay() {
        val building = selectedBuilding
        if (building.isBlank() || selectedWeek <= 0) return
        if (daySchedules[selectedDay] != null) {
            schedule = daySchedules[selectedDay]
            return
        }
        isLoadingSchedule = true
        error = ""
        when (val result = academicRepo
            .getClassroomDaySchedule(selectedArea, building, selectedWeek, selectedDay)
            .last()) {
            is Resource.Success -> {
                schedule = result.data
                daySchedules[selectedDay] = result.data
                dataVersion++
            }
            is Resource.Error -> error = result.message
            else -> Unit
        }
        isLoadingSchedule = false
    }

    suspend fun selectArea(aid: String) {
        selectedArea = aid
        selectedBuilding = ""
        buildings = emptyList()
        schedule = null
        resetDayCache()
        error = ""
        isLoadingOptions = true
        when (val result = academicRepo.getClassroomBuildings(aid).last()) {
            is Resource.Success -> {
                buildings = result.data.buildings
                selectedBuilding = buildings.firstOrNull()?.id.orEmpty()
            }
            is Resource.Error -> error = result.message
            else -> Unit
        }
        isLoadingOptions = false
        if (selectedBuilding.isNotBlank()) queryDay()
    }

    suspend fun loadOptions() {
        isLoadingOptions = true
        error = ""
        when (val result = academicRepo.getClassroomQueryOptions().last()) {
            is Resource.Success -> {
                val options = result.data
                areas = options.areas
                weeks = options.weeks.mapNotNull { it.id.toIntOrNull() }.filter { it in 1..26 }
                    .ifEmpty { (1..26).toList() }
                days = options.days.mapNotNull { it.id.toIntOrNull() }.filter { it in 1..7 }
                    .ifEmpty { (1..7).toList() }
                selectedWeek = (prefs?.currentTeachingWeek ?: weeks.first()).coerceIn(weeks.first(), weeks.last())
                isLoadingOptions = false
                areas.firstOrNull()?.let { selectArea(it.id) } ?: run { isLoadingOptions = false }
            }
            is Resource.Error -> {
                error = result.message
                isLoadingOptions = false
            }
            else -> isLoadingOptions = false
        }
    }

    suspend fun loadWeek() {
        if (isWeekLoading || selectedBuilding.isBlank() || selectedWeek <= 0) return
        isWeekLoading = true
        weekProgress = 0
        error = ""
        for (day in 1..7) {
            if (daySchedules[day] == null) {
                when (val result = academicRepo
                    .getClassroomDaySchedule(selectedArea, selectedBuilding, selectedWeek, day)
                    .last()) {
                    is Resource.Success -> daySchedules[day] = result.data
                    is Resource.Error -> {
                        error = result.message
                        isWeekLoading = false
                        return
                    }
                    else -> Unit
                }
            }
            weekProgress = day
        }
        weekLoaded = daySchedules.size == 7
        schedule = daySchedules[selectedDay] ?: schedule
        dataVersion++
        isWeekLoading = false
    }

    LaunchedEffect(Unit) {
        if (areas.isEmpty() && error.isBlank()) loadOptions()
    }

    // ---------------- 派生数据 ----------------

    val summaryRooms = remember(weekLoaded, selectedDay, dataVersion) {
        val source: List<ClassroomRoom> = if (weekLoaded) {
            val merged = LinkedHashMap<String, ClassroomRoom>()
            (1..7).forEach { day ->
                daySchedules[day]?.rooms?.forEach { room ->
                    val key = room.id.ifBlank { room.name }
                    val existing = merged[key]
                    merged[key] = if (existing == null) {
                        room
                    } else {
                        existing.copy(occupancy = existing.occupancy + room.occupancy)
                    }
                }
            }
            merged.values.toList()
        } else {
            schedule?.rooms.orEmpty()
        }

        source.map { room ->
            val ranges = if (weekLoaded) {
                ClassroomScheduleLogic.weekRanges(room.occupancy)
            } else {
                ClassroomScheduleLogic.dayRanges(room.occupancy, selectedDay)
            }
            RoomSummary(
                room = room,
                ranges = ranges,
                labels = ranges.map(ClassroomScheduleLogic::formatRange),
                occupiedCount = ClassroomScheduleLogic.occupiedSections(room.occupancy),
                freeSpan = ranges.sumOf { it.span }
            )
        }.sortedWith(
            compareByDescending<RoomSummary> { it.freeSpan }
                .thenBy { it.occupiedCount }
                .thenBy { it.room.name }
        )
    }

    val filteredRooms = remember(summaryRooms, searchQuery, onlyFree, minSpan) {
        summaryRooms.mapNotNull { item ->
            val keyword = searchQuery.trim()
            if (keyword.isNotEmpty() &&
                !item.room.name.contains(keyword, ignoreCase = true) &&
                !item.room.roomType.contains(keyword, ignoreCase = true)
            ) {
                return@mapNotNull null
            }
            val visible = item.ranges.filter { it.span >= minSpan }
            if (onlyFree && visible.isEmpty()) return@mapNotNull null
            item.copy(
                ranges = visible,
                labels = visible.map(ClassroomScheduleLogic::formatRange)
            )
        }
    }

    val allDayFree = summaryRooms.count { it.occupiedCount == 0 }
    val studyable = summaryRooms.count { it.ranges.any { range -> range.span >= 2 } }

    val dayLabel = WEEKDAY_TEXTS.getOrNull(selectedDay - 1) ?: ""
    val buildingName = buildings.firstOrNull { it.id == selectedBuilding }?.name
        ?: schedule?.buildingName.orEmpty()

    // ---------------- 页面 ----------------

    val listState = rememberLazyListState()
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                top = pageTopBarTopPadding(),
                bottom = LocalBottomContentInset.current + 24.dp
            )
        ) {
            item {
                FilterCard(
                    areas = areas,
                    buildings = buildings,
                    weeks = weeks,
                    days = days,
                    selectedArea = selectedArea,
                    selectedBuilding = selectedBuilding,
                    selectedWeek = selectedWeek,
                    selectedDay = selectedDay,
                    isLoading = isLoadingOptions || isLoadingSchedule,
                    isWeekLoading = isWeekLoading,
                    weekLoaded = weekLoaded,
                    weekProgress = weekProgress,
                    onAreaSelected = { aid -> coroutineScope.launch { selectArea(aid) } },
                    onBuildingSelected = { id ->
                        selectedBuilding = id
                        schedule = null
                        resetDayCache()
                        coroutineScope.launch { queryDay() }
                    },
                    onWeekSelected = { week ->
                        selectedWeek = week
                        schedule = null
                        resetDayCache()
                        coroutineScope.launch { queryDay() }
                    },
                    onDaySelected = { day ->
                        selectedDay = day
                        coroutineScope.launch { queryDay() }
                    },
                    onQuery = { coroutineScope.launch { queryDay() } },
                    onToday = {
                        selectedWeek = (prefs?.currentTeachingWeek ?: selectedWeek)
                            .coerceIn(weeks.firstOrNull() ?: 1, weeks.lastOrNull() ?: 26)
                        selectedDay = LocalDate.now().dayOfWeek.value
                        schedule = null
                        resetDayCache()
                        coroutineScope.launch { queryDay() }
                    },
                    onLoadWeek = { coroutineScope.launch { loadWeek() } }
                )
            }

            if (error.isNotBlank()) {
                item {
                    ErrorView(
                        message = error,
                        onRetry = { coroutineScope.launch { loadOptions() } },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (buildingName.isNotBlank()) BadgePill(buildingName)
                    if (selectedWeek > 0) BadgePill("第 $selectedWeek 周")
                    if (dayLabel.isNotBlank()) BadgePill("$dayLabel${schedule?.date?.let { "（$it）" } ?: ""}")
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = if (summaryRooms.isEmpty()) "" else
                            "共 ${summaryRooms.size} 间 · 整天无课 $allDayFree · 可自习 $studyable",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            item {
                SectionCard(title = "教室占用矩阵") {
                    when {
                        (isLoadingSchedule || isLoadingOptions) && schedule == null -> LoadingView(
                            message = "正在读取教务排课…",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                        )
                        schedule == null -> EmptyView(
                            title = "尚未查询",
                            description = "选择教学区与教学楼后点击「查询」",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                        )
                        summaryRooms.isEmpty() -> EmptyView(
                            title = "暂无教室数据",
                            description = "该教学楼在所选周次没有返回教室",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                        )
                        else -> ClassroomMatrix(
                            rooms = summaryRooms.take(visibleLimit),
                            day = selectedDay,
                            isDark = isDark,
                            showMoreCount = (summaryRooms.size - visibleLimit).coerceAtLeast(0),
                            onShowMore = { visibleLimit += 40 }
                        )
                    }
                }
            }

            item {
                SectionCard(title = "空闲时段") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("筛选教室（如 0303）") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .registerInputField(),
                            shape = AppTextFieldShape
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            (1..3).forEach { span ->
                                FilterChip(
                                    selected = minSpan == span,
                                    onClick = { minSpan = span },
                                    label = { Text("连续≥${span}节", fontSize = 11.sp) }
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            FilterChip(
                                selected = onlyFree,
                                onClick = { onlyFree = !onlyFree },
                                label = { Text("只看有空档", fontSize = 11.sp) }
                            )
                        }

                        if (filteredRooms.isEmpty()) {
                            EmptyView(
                                title = "没有匹配的教室",
                                description = "换个关键字或放宽连续节次",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                            )
                        } else {
                            filteredRooms.take(30).forEach { item ->
                                RoomFreeRow(item)
                            }
                            if (filteredRooms.size > 30) {
                                Text(
                                    text = "仅显示前 30 间，请用关键字缩小范围",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }

                        Text(
                            text = if (weekLoaded) {
                                "已加载整周（周一至周日），空闲时段为连续合并结果，最少 $minSpan 节。"
                            } else {
                                "当前为单日结果（$dayLabel）。点击「加载整周」可补齐周一至周日，用于规划整周自习。"
                            } + "数据来自教务「教室时间占用」，座位数为教务登记值。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }

        FixedPageTopBar(title = "自习空教室", onBack = onBack)
    }
}

/* ------------------------------------------------------------------ *
 * 筛选区
 * ------------------------------------------------------------------ */

@Composable
private fun FilterCard(
    areas: List<NamedOption>,
    buildings: List<NamedOption>,
    weeks: List<Int>,
    days: List<Int>,
    selectedArea: String,
    selectedBuilding: String,
    selectedWeek: Int,
    selectedDay: Int,
    isLoading: Boolean,
    isWeekLoading: Boolean,
    weekLoaded: Boolean,
    weekProgress: Int,
    onAreaSelected: (String) -> Unit,
    onBuildingSelected: (String) -> Unit,
    onWeekSelected: (Int) -> Unit,
    onDaySelected: (Int) -> Unit,
    onQuery: () -> Unit,
    onToday: () -> Unit,
    onLoadWeek: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChipRow(
                label = "教学区",
                items = areas.map { it.id to it.name },
                selected = selectedArea,
                onSelected = onAreaSelected
            )
            FilterChipRow(
                label = "教学楼",
                items = buildings.map { it.id to it.name },
                selected = selectedBuilding,
                onSelected = onBuildingSelected
            )
            FilterChipRow(
                label = "教学周",
                items = weeks.map { it.toString() to "第 $it 周" },
                selected = selectedWeek.takeIf { it > 0 }?.toString().orEmpty(),
                onSelected = { onWeekSelected(it.toIntOrNull() ?: 1) }
            )
            FilterChipRow(
                label = "星期",
                items = days.map { it.toString() to (WEEKDAY_TEXTS.getOrNull(it - 1) ?: "周$it") },
                selected = selectedDay.toString(),
                onSelected = { onDaySelected(it.toIntOrNull() ?: 1) }
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onQuery,
                    enabled = selectedBuilding.isNotBlank() && !isLoading,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("查询", fontSize = 13.sp)
                }
                TextButton(onClick = onToday, enabled = !isLoading) {
                    Text("本周", fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onLoadWeek, enabled = !isWeekLoading && !weekLoaded && selectedBuilding.isNotBlank()) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = when {
                            weekLoaded -> "整周已加载"
                            isWeekLoading -> "加载中 $weekProgress/7"
                            else -> "加载整周"
                        },
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChipRow(
    label: String,
    items: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(52.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items, key = { it.first }) { (value, name) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelected(value) },
                    label = { Text(name, fontSize = 11.sp) }
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ *
 * 占用矩阵
 * ------------------------------------------------------------------ */

@Composable
private fun ClassroomMatrix(
    rooms: List<RoomSummary>,
    day: Int,
    isDark: Boolean,
    showMoreCount: Int,
    onShowMore: () -> Unit
) {
    val scrollState = rememberScrollState()
    val occupiedColor = if (isDark) Color(0xFF3F3F46) else Color(0xFFE4E4E7)
    val freeColor = if (isDark) Color(0xFF10B981).copy(alpha = 0.16f) else Color(0xFFECFDF5)
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "占用标记：● 课程 / 考试 / 其它，浅绿为可自习",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        Column(modifier = Modifier.horizontalScroll(scrollState)) {
            // 表头
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.width(ROOM_COLUMN_WIDTH.dp).padding(vertical = 6.dp)
                ) {
                    Text(
                        text = "教室",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                (1..ClassroomScheduleLogic.SECTION_COUNT).forEach { section ->
                    Box(
                        modifier = Modifier.width(SECTION_CELL_SIZE.dp).height(26.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$section",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            rooms.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        modifier = Modifier
                            .width(ROOM_COLUMN_WIDTH.dp)
                            .padding(end = 6.dp, top = 4.dp, bottom = 4.dp)
                    ) {
                        Text(
                            text = item.room.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                append(item.room.roomType.ifBlank { "类型未知" })
                                if (item.room.seats > 0) append(" · ${item.room.seats}座")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    (1..ClassroomScheduleLogic.SECTION_COUNT).forEach { section ->
                        val occupancy = item.room.occupancy.firstOrNull {
                            it.day == day && section >= it.start && section < it.start + it.span
                        }
                        Box(
                            modifier = Modifier
                                .padding(1.dp)
                                .size(SECTION_CELL_SIZE.dp)
                                .background(
                                    color = if (occupancy == null) freeColor else occupiedColor,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .border(0.5.dp, borderColor, RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (occupancy != null) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(occupancyColor(occupancy.type), RoundedCornerShape(3.dp))
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showMoreCount > 0) {
            TextButton(onClick = onShowMore) {
                Text("再显示 ${minOf(40, showMoreCount)} 间（共 ${rooms.size + showMoreCount} 间）", fontSize = 12.sp)
            }
        }
    }
}

private fun occupancyColor(type: String): Color = when (type) {
    "course" -> Color(0xFFE11D48)
    "exam" -> Color(0xFFF59E0B)
    "other" -> Color(0xFF0EA5E9)
    else -> Color(0xFF71717A)
}

/* ------------------------------------------------------------------ *
 * 空闲时段行
 * ------------------------------------------------------------------ */

@Composable
private fun RoomFreeRow(item: RoomSummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.room.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(item.room.roomType.ifBlank { "类型未知" })
                    if (item.room.seats > 0) append(" · ${item.room.seats} 座")
                    if (item.occupiedCount > 0) append(" · 已排 ${item.occupiedCount} 节")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (item.labels.isEmpty()) {
                Text(
                    text = "已排满",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                item.labels.take(2).forEach { label ->
                    FreeSlotChip(label)
                }
                if (item.labels.size > 2) {
                    Text(
                        text = "+${item.labels.size - 2}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun FreeSlotChip(label: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFF10B981).copy(alpha = 0.12f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF047857),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun BadgePill(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

/* ------------------------------------------------------------------ *
 * 通用卡片
 * ------------------------------------------------------------------ */

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}


