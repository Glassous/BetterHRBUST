package com.glassous.betterhrbust.feature.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glassous.betterhrbust.core.model.CustomCourseEntry
import com.glassous.betterhrbust.core.model.RescheduleSourceMatch
import com.glassous.betterhrbust.core.model.TimetableRescheduleEntry
import com.glassous.betterhrbust.core.ui.theme.AppShapes
import com.glassous.betterhrbust.core.util.TimetableOverrides

/**
 * 添加 / 编辑实验课与自定义课程的底部面板。
 * 支持「选择第几节」与「自定义几点到几点」两种时间方式。
 */
@Composable
internal fun CustomCourseEditorSheet(
    initialEntry: CustomCourseEntry,
    isEditing: Boolean,
    maxTeachingWeek: Int,
    quickSuggestions: List<Pair<String, String>>,
    onSave: (CustomCourseEntry) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var category by remember(initialEntry) { mutableStateOf(initialEntry.category.ifBlank { "lab" }) }
    var courseName by remember(initialEntry) { mutableStateOf(initialEntry.courseName) }
    var location by remember(initialEntry) { mutableStateOf(initialEntry.location) }
    var teacher by remember(initialEntry) { mutableStateOf(initialEntry.teacher) }
    var note by remember(initialEntry) { mutableStateOf(initialEntry.note) }
    var week by remember(initialEntry) { mutableIntStateOf(initialEntry.week.coerceIn(1, maxTeachingWeek)) }
    var day by remember(initialEntry) { mutableIntStateOf(initialEntry.day.coerceIn(1, 7)) }
    var timeMode by remember(initialEntry) { mutableStateOf(initialEntry.timeMode.ifBlank { "slot" }) }
    var slotType by remember(initialEntry) { mutableStateOf(initialEntry.slotType.ifBlank { "combine" }) }
    var slotPeriod by remember(initialEntry) { mutableIntStateOf(initialEntry.slotPeriod.coerceAtLeast(1)) }
    var slotSpan by remember(initialEntry) { mutableIntStateOf(initialEntry.slotSpan.coerceIn(1, 4)) }

    val defaultSlot = remember(initialEntry) {
        val list = if (initialEntry.slotType == "base") BaseSlots else CombineSlots
        list.getOrNull((initialEntry.slotPeriod - 1).coerceIn(0, list.lastIndex)) ?: CombineSlots[0]
    }
    var startTime by remember(initialEntry) {
        mutableStateOf(initialEntry.startTime.ifBlank { defaultSlot.start })
    }
    var endTime by remember(initialEntry) {
        mutableStateOf(initialEntry.endTime.ifBlank { defaultSlot.end })
    }

    var nameError by remember { mutableStateOf("") }
    var timeError by remember { mutableStateOf("") }
    var showWeekMenu by remember { mutableStateOf(false) }
    var editingTimeTarget by remember { mutableStateOf<String?>(null) } // "start" | "end" | null

    val slots = if (slotType == "base") BaseSlots else CombineSlots

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AppShapes.Sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = if (isEditing) "编辑实验 / 自定义课程" else "添加实验 / 自定义课程",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            // 类型切换：实验课 / 自定义课程
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("lab" to "实验课", "custom" to "自定义课程").forEach { (value, label) ->
                    val selected = category == value
                    FilterChip(
                        selected = selected,
                        onClick = { category = value },
                        label = { Text(label) },
                        leadingIcon = if (value == "lab") {
                            { Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 快捷填入本学期课程 / 未排课课程
            if (!isEditing && quickSuggestions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "快捷填入本学期课程（可选）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        quickSuggestions.forEach { (name, sugTeacher) ->
                            AssistChip(
                                onClick = {
                                    courseName = name
                                    nameError = ""
                                    if (teacher.isBlank() && sugTeacher.isNotBlank()) {
                                        teacher = sugTeacher
                                    }
                                },
                                label = { Text(name, maxLines = 1) }
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = courseName,
                onValueChange = {
                    courseName = it
                    if (nameError.isNotEmpty() && it.isNotBlank()) nameError = ""
                },
                label = { Text(if (category == "lab") "实验名称 *" else "课程名称 *") },
                placeholder = { Text(if (category == "lab") "如：大学物理实验（一）" else "如：学科竞赛培训") },
                singleLine = true,
                isError = nameError.isNotEmpty(),
                supportingText = if (nameError.isNotEmpty()) {
                    { Text(nameError) }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )

            // 周次与星期
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    OutlinedButton(
                        onClick = { showWeekMenu = true },
                        shape = AppShapes.InnerCard
                    ) {
                        Text("第 $week 周")
                    }
                    DropdownMenu(
                        expanded = showWeekMenu,
                        onDismissRequest = { showWeekMenu = false },
                        modifier = Modifier.heightIn(max = 280.dp)
                    ) {
                        (1..maxTeachingWeek).forEach { w ->
                            DropdownMenuItem(
                                text = { Text("第 $w 周") },
                                onClick = {
                                    week = w
                                    showWeekMenu = false
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    (1..7).forEach { d ->
                        val selected = day == d
                        FilterChip(
                            selected = selected,
                            onClick = { day = d },
                            label = { Text(DayShortNames[d - 1]) }
                        )
                    }
                }
            }

            // 时间安排方式：选择第几节 / 自定义几点到几点
            Surface(
                shape = AppShapes.InnerCard,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "时间安排",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("slot" to "选择第几节", "custom" to "自定义时间").forEach { (mode, label) ->
                            FilterChip(
                                selected = timeMode == mode,
                                onClick = {
                                    timeMode = mode
                                    timeError = ""
                                },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (timeMode == "slot") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = slotType == "combine",
                                onClick = {
                                    if (slotType != "combine") {
                                        slotType = "combine"
                                        slotPeriod = ((slotPeriod + 1) / 2).coerceIn(1, CombineSlots.size)
                                    }
                                },
                                label = { Text("按大节 (1-6)") }
                            )
                            FilterChip(
                                selected = slotType == "base",
                                onClick = {
                                    if (slotType != "base") {
                                        slotType = "base"
                                        slotPeriod = ((slotPeriod - 1) * 2 + 1).coerceIn(1, BaseSlots.size)
                                    }
                                },
                                label = { Text("按小节 (1-12)") }
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            slots.forEach { slot ->
                                FilterChip(
                                    selected = slotPeriod == slot.period,
                                    onClick = { slotPeriod = slot.period },
                                    label = {
                                        Text(
                                            if (slotType == "combine") "第${slot.period}大节 (${slot.start})"
                                            else "第${slot.period}节 (${slot.start})"
                                        )
                                    }
                                )
                            }
                        }

                        if (slotType == "base") {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "连堂：",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                (1..4).forEach { span ->
                                    FilterChip(
                                        selected = slotSpan == span,
                                        onClick = { slotSpan = span },
                                        label = { Text("${span}节") }
                                    )
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            TimePickBox(
                                label = "开始时间",
                                timeText = startTime,
                                onClick = { editingTimeTarget = "start" },
                                modifier = Modifier.weight(1f)
                            )
                            TimePickBox(
                                label = "结束时间",
                                timeText = endTime,
                                onClick = { editingTimeTarget = "end" },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (timeError.isNotEmpty()) {
                            Text(
                                text = timeError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            val anchor = TimetableOverrides.resolveCustomTimeAnchor(startTime, endTime)
                            Text(
                                text = "将停靠在第 ${anchor.combineSection} 大节格，并保留显示 $startTime - $endTime",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text(if (category == "lab") "实验室 / 教室" else "上课地点") },
                placeholder = { Text("如：西区实验楼 B304") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text("指导教师") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注 / 分组") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isEditing) {
                    OutlinedButton(
                        onClick = { onDelete(initialEntry.id) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("删除")
                    }
                } else {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }

                Button(
                    onClick = {
                        if (courseName.isBlank()) {
                            nameError = "请填写实验或课程名称"
                            return@Button
                        }
                        if (timeMode == "custom") {
                            val s = TimetableOverrides.parseTimeToMinutes(startTime)
                            val e = TimetableOverrides.parseTimeToMinutes(endTime)
                            if (s == null || e == null || e <= s) {
                                timeError = "结束时间必须晚于开始时间"
                                return@Button
                            }
                        }
                        onSave(
                            TimetableOverrides.normalizeCustomCourse(
                                initialEntry.copy(
                                    category = category,
                                    courseName = courseName,
                                    location = location,
                                    teacher = teacher,
                                    week = week,
                                    day = day,
                                    timeMode = timeMode,
                                    slotType = slotType,
                                    slotPeriod = slotPeriod,
                                    slotSpan = slotSpan,
                                    startTime = startTime,
                                    endTime = endTime,
                                    note = note
                                )
                            )
                        )
                    }
                ) {
                    Text(if (isEditing) "保存修改" else "添加到课表")
                }
            }
        }
    }

    val activeTimeTarget = editingTimeTarget
    if (activeTimeTarget != null) {
        val currentText = if (activeTimeTarget == "start") startTime else endTime
        val mins = TimetableOverrides.parseTimeToMinutes(currentText) ?: (14 * 60)
        val pickerState = rememberTimePickerState(
            initialHour = mins / 60,
            initialMinute = mins % 60,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { editingTimeTarget = null },
            title = { Text(if (activeTimeTarget == "start") "选择开始时间" else "选择结束时间") },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val formatted = "%02d:%02d".format(pickerState.hour, pickerState.minute)
                        if (activeTimeTarget == "start") startTime = formatted else endTime = formatted
                        timeError = ""
                        editingTimeTarget = null
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { editingTimeTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun TimePickBox(
    label: String,
    timeText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(AppShapes.InnerCard)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AccessTime,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = timeText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 单周临时调课确认 / 编辑底部面板。
 * 强制校验调整后的位置与原位置不同，支持单独自定义教室、教师与备注。
 */
@Composable
internal fun RescheduleCourseSheet(
    week: Int,
    sectionMode: SectionMode,
    sourceMatch: RescheduleSourceMatch,
    existingId: String,
    initialTargetDay: Int,
    initialTargetPeriod: Int,
    initialLocation: String,
    initialTeacher: String,
    initialNote: String,
    onSave: (TimetableRescheduleEntry) -> Unit,
    onRestore: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val slots = slotsOf(sectionMode)

    var targetDay by remember(sourceMatch, initialTargetDay) {
        mutableIntStateOf(initialTargetDay.coerceIn(1, 7))
    }
    var targetPeriod by remember(sourceMatch, initialTargetPeriod, sectionMode) {
        mutableIntStateOf(initialTargetPeriod.coerceIn(1, slots.size))
    }
    var location by remember(sourceMatch, initialLocation) { mutableStateOf(initialLocation) }
    var teacher by remember(sourceMatch, initialTeacher) { mutableStateOf(initialTeacher) }
    var note by remember(sourceMatch, initialNote) { mutableStateOf(initialNote) }

    val isPositionValid = TimetableOverrides.isDifferentRescheduleTarget(
        sourceMatch = sourceMatch,
        targetDay = targetDay,
        targetPeriod = targetPeriod,
        sectionMode = sectionMode
    )

    val originPeriod = if (sectionMode == SectionMode.BASE) {
        sourceMatch.originBaseSections.firstOrNull() ?: ((sourceMatch.originCombineSection - 1) * 2 + 1)
    } else {
        sourceMatch.originCombineSection
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AppShapes.Sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (existingId.isNotEmpty()) "修改单周临时调课" else "确认单周临时调课",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // 原排课对照卡
            Surface(
                shape = AppShapes.InnerCard,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = sourceMatch.courseName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = AppShapes.Pill,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "仅第 $week 周",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Text(
                        text = "原安排：${TimetableOverrides.formatDaySectionLabel(sourceMatch.originDay, originPeriod, sectionMode)} · ${sourceMatch.originLocation.ifBlank { "待定" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "调整到星期",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    (1..7).forEach { d ->
                        FilterChip(
                            selected = targetDay == d,
                            onClick = { targetDay = d },
                            label = { Text(DayNames[d - 1]) }
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (sectionMode == SectionMode.BASE) "调整到第几小节" else "调整到第几大节",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    slots.forEach { slot ->
                        FilterChip(
                            selected = targetPeriod == slot.period,
                            onClick = { targetPeriod = slot.period },
                            label = {
                                Text(
                                    if (sectionMode == SectionMode.BASE) "第${slot.period}节 (${slot.start})"
                                    else "第${slot.period}大节 (${slot.start})"
                                )
                            }
                        )
                    }
                }
            }

            if (!isPositionValid) {
                Surface(
                    shape = AppShapes.InnerCard,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "调整后的课所在位置必须与原有位置不同，请选择其它星期或节次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }

            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text("调整后教室（可单独自定义）") },
                placeholder = { Text("留空则显示“待定”") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text("任课教师") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("调课备注") },
                    placeholder = { Text("选填") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (existingId.isNotEmpty()) {
                    OutlinedButton(onClick = { onRestore(existingId) }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("还原原排课")
                    }
                } else {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }

                Button(
                    enabled = isPositionValid,
                    onClick = {
                        val targetCombine = if (sectionMode == SectionMode.BASE) {
                            (targetPeriod + 1) / 2
                        } else {
                            targetPeriod
                        }
                        val targetBase = if (sectionMode == SectionMode.BASE) {
                            targetPeriod
                        } else {
                            (targetCombine - 1) * 2 + 1
                        }
                        onSave(
                            TimetableOverrides.normalizeReschedule(
                                TimetableRescheduleEntry(
                                    id = existingId,
                                    week = week,
                                    sourceMatch = sourceMatch,
                                    targetDay = targetDay,
                                    targetSectionMode = if (sectionMode == SectionMode.BASE) "base" else "combine",
                                    targetCombineSection = targetCombine,
                                    targetBaseSection = targetBase,
                                    location = location,
                                    teacher = teacher,
                                    note = note
                                )
                            )
                        )
                    }
                ) {
                    Text("确认调课")
                }
            }
        }
    }
}
