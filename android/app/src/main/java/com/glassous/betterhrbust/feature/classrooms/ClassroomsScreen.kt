package com.glassous.betterhrbust.feature.classrooms

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.datastore.UserPreferencesManager
import com.glassous.betterhrbust.core.model.ClassroomDaySchedule
import com.glassous.betterhrbust.core.model.ClassroomOccupancy
import com.glassous.betterhrbust.core.model.ClassroomRoom
import com.glassous.betterhrbust.core.model.NamedOption
import com.glassous.betterhrbust.core.network.SessionExpiredException
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

@Composable
fun ClassroomsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val app = remember { BetterHrbustApp.instance }
    val academicRepo = remember { app.academicRepository }
    val authRepo = remember { app.authRepository }
    val prefsManager = remember { app.preferencesManager }
    val prefs by prefsManager.preferencesFlow.collectAsState(initial = null)
    val coroutineScope = rememberCoroutineScope()
    val isDark = LocalDarkTheme.current

    val sessionExpired by authRepo.isSessionExpired.collectAsState()
    var showLoginDialog by remember { mutableStateOf(false) }

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

    var isLoadingOptions by remember { mutableStateOf(false) }
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

    /**
     * 所有查询的统一闸门：
     *  - 会话失效 → 标记失效并原地弹出快捷登录卡片，不发起请求
     *  - 请求过程中发现会话失效（SessionExpiredException）→ 同样弹卡片
     */
    suspend fun <T> guardedCall(block: suspend () -> Resource<T>): Resource<T>? {
        if (authRepo.isSessionExpired.value) {
            showLoginDialog = true
            return null
        }
        return try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (expired: SessionExpiredException) {
            authRepo.markSessionExpired(true)
            showLoginDialog = true
            null
        } catch (e: Exception) {
            error = e.message ?: "查询失败，请检查网络后重试"
            null
        }
    }

    suspend fun queryDay() {
        val building = selectedBuilding
        if (building.isBlank() || selectedWeek <= 0) return
        if (daySchedules[selectedDay] != null) {
            schedule = daySchedules[selectedDay]
            return
        }
        if (isLoadingSchedule) return
        isLoadingSchedule = true
        error = ""
        val result = guardedCall {
            academicRepo.getClassroomDaySchedule(selectedArea, building, selectedWeek, selectedDay).last()
        }
        when (result) {
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
        val result = guardedCall { academicRepo.getClassroomBuildings(aid).last() }
        when (result) {
            is Resource.Success -> {
                buildings = result.data.buildings
                // 默认教学楼（需与当前教学区一致）优先，其次第一个
                val preferred = prefs
                    ?.takeIf { it.classroomDefaultAreaId == aid }
                    ?.classroomDefaultBuildingId
                selectedBuilding = ClassroomScheduleLogic.pickBuildingId(
                    buildings.map { it.id },
                    preferred
                )
            }
            is Resource.Error -> error = result.message
            else -> Unit
        }
        isLoadingOptions = false
        if (selectedBuilding.isNotBlank()) queryDay()
    }

    suspend fun loadOptions() {
        if (isLoadingOptions) return
        isLoadingOptions = true
        error = ""
        val result = guardedCall { academicRepo.getClassroomQueryOptions().last() }
        when (result) {
            is Resource.Success -> {
                val options = result.data
                areas = options.areas
                weeks = options.weeks.mapNotNull { it.id.toIntOrNull() }.filter { it in 1..26 }
                    .ifEmpty { (1..26).toList() }
                days = options.days.mapNotNull { it.id.toIntOrNull() }.filter { it in 1..7 }
                    .ifEmpty { (1..7).toList() }
                selectedWeek = (prefs?.currentTeachingWeek ?: weeks.first()).coerceIn(weeks.first(), weeks.last())

                // 默认教学楼所在教学区优先，其次第一个教学区
                val defaultArea = prefs?.classroomDefaultAreaId.orEmpty()
                val areaId = if (defaultArea.isNotBlank() && areas.any { it.id == defaultArea }) {
                    defaultArea
                } else {
                    areas.firstOrNull()?.id.orEmpty()
                }
                isLoadingOptions = false
                if (areaId.isNotBlank()) selectArea(areaId)
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
                val result = guardedCall {
                    academicRepo
                        .getClassroomDaySchedule(selectedArea, selectedBuilding, selectedWeek, day)
                        .last()
                }
                when (result) {
                    is Resource.Success -> daySchedules[day] = result.data
                    is Resource.Error -> {
                        error = result.message
                        isWeekLoading = false
                        return
                    }
                    else -> {
                        // 会话失效或网络异常：中断整周加载
                        if (daySchedules[day] == null) {
                            isWeekLoading = false
                            return
                        }
                    }
                }
            }
            weekProgress = day
        }
        weekLoaded = daySchedules.size == 7
        schedule = daySchedules[selectedDay] ?: schedule
        dataVersion++
        isWeekLoading = false
    }

    /** 设置 / 取消默认教学楼 */
    fun toggleDefaultBuilding() {
        val snapshot = prefs ?: return
        if (selectedArea.isBlank() || selectedBuilding.isBlank()) return
        val buildingName = buildings.firstOrNull { it.id == selectedBuilding }?.name.orEmpty()
        coroutineScope.launch {
            val isDefault = snapshot.classroomDefaultAreaId == selectedArea &&
                snapshot.classroomDefaultBuildingId == selectedBuilding
            if (isDefault) {
                prefsManager.clearClassroomDefaultBuilding(snapshot)
            } else {
                prefsManager.setClassroomDefaultBuilding(
                    selectedArea,
                    selectedBuilding,
                    buildingName,
                    snapshot
                )
            }
        }
    }

    // 进入页面先检查登录状态：已失效则不发请求，直接提供原地登录
    LaunchedEffect(prefs, sessionExpired) {
        val snapshot = prefs ?: return@LaunchedEffect
        if (areas.isNotEmpty() || error.isNotBlank() || showLoginDialog) return@LaunchedEffect
        if (sessionExpired || snapshot.sessionExpired) {
            isLoadingOptions = false
            showLoginDialog = true
        } else {
            loadOptions()
        }
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
    val isDefaultBuilding = prefs?.let {
        it.classroomDefaultAreaId == selectedArea && it.classroomDefaultBuildingId == selectedBuilding
    } == true

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
                    isDefaultBuilding = isDefaultBuilding,
                    onToggleDefaultBuilding = ::toggleDefaultBuilding,
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

        // 原地快捷登录：登录成功后关闭弹窗并重新拉取本页数据
        if (showLoginDialog) {
            ClassroomQuickLoginDialog(
                onDismiss = { showLoginDialog = false },
                onSuccess = {
                    showLoginDialog = false
                    error = ""
                    coroutineScope.launch { loadOptions() }
                }
            )
        }
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
    isDefaultBuilding: Boolean,
    onToggleDefaultBuilding: () -> Unit,
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
                onSelected = onBuildingSelected,
                trailing = {
                    IconButton(
                        onClick = onToggleDefaultBuilding,
                        enabled = selectedBuilding.isNotBlank(),
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = if (isDefaultBuilding) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (isDefaultBuilding) "取消默认教学楼" else "设为默认教学楼",
                            tint = if (isDefaultBuilding) Color(0xFFF59E0B) else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
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
                    Text("今天", fontSize = 13.sp)
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
    onSelected: (String) -> Unit,
    trailing: (@Composable () -> Unit)? = null
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
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(items, key = { it.first }) { (value, name) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelected(value) },
                    label = { Text(name, fontSize = 11.sp) }
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(4.dp))
            trailing()
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
    val occupiedColor = if (isDark) Color(0xFF3F3F46) else Color(0xFFE4E4E7)
    val freeColor = if (isDark) Color(0xFF10B981).copy(alpha = 0.16f) else Color(0xFFECFDF5)
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "占用标记：● 课程 / 考试 / 其它，浅绿为可自习",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )

        // 宽屏（平板 / 横屏）走矩阵，窄屏改为每间教室一张卡片，二者共用同一份占用数据
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth >= 600.dp) {
                val sectionCellSize = ((maxWidth - ROOM_COLUMN_WIDTH.dp) /
                    ClassroomScheduleLogic.SECTION_COUNT).coerceIn(24.dp, 56.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    MatrixTable(
                        rooms = rooms,
                        day = day,
                        sectionCellSize = sectionCellSize,
                        occupiedColor = occupiedColor,
                        freeColor = freeColor,
                        borderColor = borderColor
                    )
                }
            } else {
                MatrixCards(
                    rooms = rooms,
                    day = day,
                    occupiedColor = occupiedColor,
                    freeColor = freeColor
                )
            }
        }

        if (showMoreCount > 0) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = onShowMore) {
                    Text(
                        "再显示 ${minOf(40, showMoreCount)} 间（共 ${rooms.size + showMoreCount} 间）",
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

/** 宽屏矩阵：教室首列固定宽度，小节列按可用宽度均分 */
@Composable
private fun MatrixTable(
    rooms: List<RoomSummary>,
    day: Int,
    sectionCellSize: Dp,
    occupiedColor: Color,
    freeColor: Color,
    borderColor: Color
) {
    Column {
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
                    modifier = Modifier.width(sectionCellSize).height(26.dp),
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
                        .padding(end = 6.dp, top = 3.dp, bottom = 3.dp)
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
                    val occupancy = occupancyAt(item.room, day, section)
                    Box(
                        modifier = Modifier
                            .padding(1.dp)
                            .size(sectionCellSize)
                            .height(28.dp)
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
}

/** 窄屏卡片：每间教室 12 个小节铺满一行，不需要横向滚动 */
@Composable
private fun MatrixCards(
    rooms: List<RoomSummary>,
    day: Int,
    occupiedColor: Color,
    freeColor: Color
) {
    Column {
        rooms.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.room.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = buildString {
                            append(item.room.roomType.ifBlank { "类型未知" })
                            if (item.room.seats > 0) append(" · ${item.room.seats}座")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    (1..ClassroomScheduleLogic.SECTION_COUNT).forEach { section ->
                        val occupancy = occupancyAt(item.room, day, section)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(18.dp)
                                .background(
                                    color = if (occupancy == null) freeColor else occupiedColor,
                                    shape = RoundedCornerShape(3.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (occupancy != null) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .background(occupancyColor(occupancy.type), RoundedCornerShape(3.dp))
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf("第1节", "第6节", "第12节").forEach { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

/** 某间教室在某天某小节是否被占用 */
private fun occupancyAt(
    room: ClassroomRoom,
    day: Int,
    section: Int
): ClassroomOccupancy? =
    room.occupancy.firstOrNull { it.day == day && section >= it.start && section < it.start + it.span }

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

/* ------------------------------------------------------------------ *
 * 原地快捷登录卡片
 * ------------------------------------------------------------------ */

/**
 * 会话失效时在当前页面弹出的紧凑登录卡片。
 * 登录成功 → [onSuccess]（页面重新拉取数据）；点「取消」/ 返回键 → [onDismiss]，停留原页面。
 */
@Composable
private fun ClassroomQuickLoginDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val app = remember { BetterHrbustApp.instance }
    val authRepo = remember { app.authRepository }
    val prefsManager = remember { app.preferencesManager }
    val prefs by prefsManager.preferencesFlow.collectAsState(initial = null)
    val coroutineScope = rememberCoroutineScope()

    var username by remember { mutableStateOf(prefs?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(false) }
    var captchaBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isCaptchaLoading by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun refreshCaptcha() {
        if (isCaptchaLoading || isLoading) return
        isCaptchaLoading = true
        captcha = ""
        coroutineScope.launch {
            try {
                val bytes = authRepo.getCaptcha()
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    errorMessage = "验证码图片无效，请点击重试"
                } else {
                    captchaBitmap = bitmap
                    errorMessage = null
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                errorMessage = e.message ?: "获取验证码失败，请检查网络"
            } finally {
                isCaptchaLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { refreshCaptcha() }

    // 回填「记住密码」凭证，仅在学号匹配时使用
    LaunchedEffect(prefs) {
        val snapshot = prefs ?: return@LaunchedEffect
        if (username.isBlank()) username = snapshot.username
        if (password.isBlank()) {
            UserPreferencesManager.resolveSavedPassword(username, snapshot.savedCredential)?.let {
                password = it
                rememberPassword = true
            }
        }
    }

    fun submit() {
        if (isLoading) return
        if (username.isBlank() || password.isBlank()) {
            errorMessage = "请输入学号和密码"
            return
        }
        if (!captcha.trim().matches(Regex("[0-9A-Za-z]{4}"))) {
            errorMessage = "请输入 4 位验证码"
            return
        }
        if (captchaBitmap == null) {
            errorMessage = "请先获取验证码"
            return
        }
        isLoading = true
        errorMessage = null
        coroutineScope.launch {
            authRepo.login(username.trim(), password, captcha.trim(), rememberPassword).collect { resource ->
                when (resource) {
                    is Resource.Loading -> isLoading = true
                    is Resource.Success -> {
                        isLoading = false
                        authRepo.markSessionExpired(false)
                        onSuccess()
                    }
                    is Resource.Error -> {
                        isLoading = false
                        errorMessage = resource.message
                        refreshCaptcha()
                    }
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "登录教务系统",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "登录状态已失效，登录后继续查询空教室",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("学号") },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .registerInputField(),
                    shape = AppTextFieldShape
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    enabled = !isLoading,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .registerInputField(),
                    shape = AppTextFieldShape
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = captcha,
                        onValueChange = { captcha = it },
                        label = { Text("验证码") },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier
                            .weight(1f)
                            .registerInputField(),
                        shape = AppTextFieldShape
                    )
                    Box(
                        modifier = Modifier
                            .size(width = 100.dp, height = 52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { refreshCaptcha() },
                        contentAlignment = Alignment.Center
                    ) {
                        val bitmap = captchaBitmap
                        when {
                            isCaptchaLoading -> CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            bitmap != null -> Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "验证码，点击刷新",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                            else -> Text(
                                text = "点击刷新",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = rememberPassword,
                        onCheckedChange = { rememberPassword = it },
                        enabled = !isLoading
                    )
                    Text(
                        text = "记住密码",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, enabled = !isLoading) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { submit() },
                        enabled = !isLoading && captchaBitmap != null,
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
                        Text("登录")
                    }
                }
            }
        }
    }
}


