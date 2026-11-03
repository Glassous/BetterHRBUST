package com.glassous.betterhrbust.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassous.betterhrbust.core.ui.LocalTopContentInset

/**
 * 页面大标题。
 *
 * 作为滚动内容的一部分渲染（而非固定的 TopAppBar），
 * 使页面内容可以穿透状态栏区域滚动；
 * 顶部安全距离由列表的 contentPadding 统一提供。
 *
 * @param onBack 传入时在标题左侧显示返回按钮（二级页面用），点击回调用于关闭该页面
 * @param emphasized 顶层主页面（概览 / 考试 / 设置）使用：字号大幅加大
 */
@Composable
fun PageHeaderTitle(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    emphasized: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回"
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = if (emphasized) 36.sp else MaterialTheme.typography.titleLarge.fontSize,
                lineHeight = if (emphasized) 44.sp else MaterialTheme.typography.titleLarge.lineHeight
            ),
            fontWeight = FontWeight.Bold
        )
    }
}

/** 固定顶栏内容区高度（不含状态栏安全区）。 */
val PageTopBarHeight = 52.dp

/**
 * 固定顶栏占用的上边距：给滚动列表的 `contentPadding.top` 用。
 *
 * @param extra 顶栏与首条内容之间的额外留白
 */
@Composable
fun pageTopBarTopPadding(extra: Dp = 8.dp): Dp =
    LocalTopContentInset.current + PageTopBarHeight + extra

/**
 * 二级页面固定顶栏：返回按钮 + 页面标题固定在状态栏下方，不随内容滚动。
 *
 * 底色始终不透明（与页面底色一致、不加分隔线），因此滚过顶栏的内容被完全遮住，
 * 也不需要随滚动状态变化。
 *
 * 使用方式：把列表 `contentPadding.top` 设为 [pageTopBarTopPadding]，
 * 顶栏与本页内容同处一个 `Box`（顶栏后放置即可覆盖在内容之上）。
 */
@Composable
fun FixedPageTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Spacer(modifier = Modifier.height(LocalTopContentInset.current))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PageTopBarHeight)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回"
                    )
                }
                Spacer(modifier = Modifier.width(2.dp))
            } else {
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun LoadingView(
    message: String = "正在加载数据...",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = "错误",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "发生错误",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(imageVector = Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("点击重试")
                }
            }
        }
    }
}

@Composable
fun EmptyView(
    title: String = "暂无数据",
    description: String = "暂时没有可展示的内容",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 指标项：用于 [ResponsiveStatGrid] */
data class StatEntry(
    val label: String,
    val value: String,
    val valueColor: Color = Color.Unspecified
)

/**
 * 自适应指标网格：
 * - 手机（窗口宽度 < [tabletWidthDp]）：每行 [compactColumns] 列（默认 2 列，即 2×N）
 * - 平板 / 横屏（窗口宽度 ≥ [tabletWidthDp]）：单行等分展示全部指标（1×N）
 *
 * 以「窗口宽度」而非容器宽度判定，避免成绩页在平板双栏布局下因列表栏变窄而误判为手机。
 */
@Composable
fun ResponsiveStatGrid(
    entries: List<StatEntry>,
    modifier: Modifier = Modifier,
    compactColumns: Int = 2,
    tabletWidthDp: Int = 600
) {
    if (entries.isEmpty()) return

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val columns = if (screenWidthDp >= tabletWidthDp) entries.size else compactColumns.coerceAtLeast(1)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        entries.chunked(columns).forEach { rowEntries ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowEntries.forEach { entry ->
                    StatEntryCell(entry = entry, modifier = Modifier.weight(1f))
                }
                // 末行不足时补占位，保证与上一行对齐
                repeat(columns - rowEntries.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatEntryCell(
    entry: StatEntry,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = entry.value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = if (entry.valueColor == Color.Unspecified) {
                MaterialTheme.colorScheme.onSurface
            } else {
                entry.valueColor
            },
            maxLines = 1
        )
        Text(
            text = entry.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val state = rememberPullToRefreshState()
    // 指示器下移，避免与穿透后的状态栏区域重叠
    val indicatorTopPadding = LocalTopContentInset.current
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = indicatorTopPadding)
            )
        },
        content = content
    )
}
