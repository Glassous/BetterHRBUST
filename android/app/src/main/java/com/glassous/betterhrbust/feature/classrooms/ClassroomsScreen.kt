package com.glassous.betterhrbust.feature.classrooms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.model.ClassroomQueryOptions
import com.glassous.betterhrbust.core.model.NamedOption
import com.glassous.betterhrbust.core.ui.AppTextFieldShape
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.components.EmptyView
import com.glassous.betterhrbust.core.ui.components.FixedPageTopBar
import com.glassous.betterhrbust.core.ui.components.LoadingView
import com.glassous.betterhrbust.core.ui.components.pageTopBarTopPadding
import com.glassous.betterhrbust.core.ui.registerInputField
import com.glassous.betterhrbust.data.repository.Resource
import kotlinx.coroutines.launch

data class FreeRoomDisplayItem(
    val roomName: String,
    val buildingName: String,
    val freeSlot: String,
    val seats: Int = 120
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassroomsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val app = remember { BetterHrbustApp.instance }
    val academicRepo = remember { app.academicRepository }
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var selectedCampus by remember { mutableStateOf("全部") }
    var options by remember { mutableStateOf<ClassroomQueryOptions?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val defaultSampleRooms = remember {
        listOf(
            FreeRoomDisplayItem("新A306", "西区 新教学楼", "全天空闲 (1-6大节)", 150),
            FreeRoomDisplayItem("新A308", "西区 新教学楼", "上午空闲 (1-2大节)", 120),
            FreeRoomDisplayItem("新B402", "西区 新教学楼", "下午空闲 (3-4大节)", 160),
            FreeRoomDisplayItem("新B501", "西区 新教学楼", "晚上空闲 (5-6大节)", 140),
            FreeRoomDisplayItem("1号楼210", "南区 1号教学楼", "全天空闲 (1-6大节)", 90),
            FreeRoomDisplayItem("1号楼315", "南区 1号教学楼", "下午空闲 (3-4大节)", 110),
            FreeRoomDisplayItem("主楼504", "西区 主教学楼", "上午空闲 (1-2大节)", 80),
            FreeRoomDisplayItem("主楼608", "西区 主教学楼", "全天空闲 (1-6大节)", 100)
        )
    }

    LaunchedEffect(Unit) {
        isLoading = true
        academicRepo.getClassroomQueryOptions().collect { res ->
            if (res is Resource.Success) {
                options = res.data
            }
            isLoading = false
        }
    }

    val filteredRooms = remember(searchQuery, selectedCampus) {
        defaultSampleRooms.filter { room ->
            val matchQuery = searchQuery.isBlank() || room.roomName.contains(searchQuery, ignoreCase = true) || room.buildingName.contains(searchQuery, ignoreCase = true)
            val matchCampus = selectedCampus == "全部" || room.buildingName.contains(selectedCampus)
            matchQuery && matchCampus
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(
            top = pageTopBarTopPadding(),
            bottom = LocalBottomContentInset.current + 24.dp
        )
    ) {
        // SearchBar
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索教室或教学楼（如 新A306）") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .registerInputField(),
                shape = AppTextFieldShape
            )
        }

        // Campus Chips
        item {
            val campusOptions = listOf("全部", "西区", "南区")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(campusOptions) { campus ->
                    FilterChip(
                        selected = selectedCampus == campus,
                        onClick = { selectedCampus = campus },
                        label = { Text(campus) }
                    )
                }
            }
        }

        item {
            Text(
                text = "空闲教室（${filteredRooms.size}）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            // 诚实标注：列表为示例数据，实时占用查询尚未接入教务系统
            Text(
                text = "以下为示例数据（含虚构的空闲时段与容量），暂未接入教务实时占用查询",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        if (filteredRooms.isEmpty()) {
            item {
                EmptyView(
                    title = "未检索到匹配空教室",
                    description = "请更换校区或搜索关键字",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                )
            }
        } else {
            items(filteredRooms) { room ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(46.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.MeetingRoom,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(text = room.roomName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(text = room.buildingName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = "示例 · ${room.freeSlot}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = "容量约 ${room.seats} 人", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
        FixedPageTopBar(title = "自习空教室", onBack = onBack)
    }
}
