package com.glassous.betterhrbust.feature.feedback

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.ui.AppTextFieldShape
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.components.FixedPageTopBar
import com.glassous.betterhrbust.core.ui.components.pageTopBarTopPadding
import com.glassous.betterhrbust.core.ui.registerInputField
import com.glassous.betterhrbust.core.ui.theme.Amber500
import com.glassous.betterhrbust.core.ui.theme.Emerald500
import com.glassous.betterhrbust.core.ui.theme.Rose500
import com.glassous.betterhrbust.core.util.FeedbackText
import com.glassous.betterhrbust.data.repository.FeedbackRepository
import kotlinx.coroutines.launch

/**
 * 意见反馈页（与 Web 端 `views/FeedbackView.vue` 同口径）。
 *
 * 结构：页头说明 → （登录门控 / 提交成功 / 反馈表单）→ 隐私说明。
 *
 * 隐私红线：只把用户主动填写的正文、选填联系方式与实名姓名文本提交到后端，
 * 不读取、不携带学号、教务内部 ID、Cookie、成绩、课表或设备信息。
 *
 * 该页面以二级覆盖层形式打开（见 MainActivity 的 SecondaryPage），
 * 因此自动获得统一的滑入 / 滑出与预测性返回动画；
 * 沉浸式由 [LocalTopContentInset] / [LocalBottomContentInset] 与 imePadding 共同保证。
 *
 * @param onBack 关闭页面（覆盖层滑出）
 * @param onGoLogin 未登录时点击「前往登录」：打开重新登录覆盖层，登录成功后本页自动放行
 */
@Composable
fun FeedbackScreen(
    onBack: () -> Unit,
    onGoLogin: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val app = remember { BetterHrbustApp.instance }
    val prefsManager = remember { app.preferencesManager }
    val feedbackRepo = remember { app.feedbackRepository }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val prefs by prefsManager.preferencesFlow.collectAsState(initial = null)

    // ---- 表单状态：覆盖层每次重新打开都是干净状态 ----
    var content by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    // 实名开关：每次进入页面都从关闭状态开始，必须手动开启（不读取任何历史偏好，与 Web 端一致）
    var realNameEnabled by remember { mutableStateOf(false) }
    var manualName by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var submittedAt by remember { mutableStateOf("") }
    var submittedSummary by remember { mutableStateOf("") }

    // 登录门控：本地保存过教务会话（studentId 非空）且未被标记失效时视为已登录。
    // 会话过期的用户与从未登录的用户走同一张门控卡片，登录成功（sessionExpired 复位）后自动放行。
    val isLoggedIn = prefs?.let { it.studentId.isNotBlank() && !it.sessionExpired } == true
    // 实名发布优先使用教务缓存中的姓名；缓存缺失时允许手动填写
    val loadedRealName = prefs?.realName.orEmpty().trim()

    val contentLength = FeedbackText.codePointLength(content)
    val contentOverflow = contentLength > FeedbackRepository.MAX_CONTENT
    val contentReady = contentLength in FeedbackRepository.MIN_CONTENT..FeedbackRepository.MAX_CONTENT
    val signatureReady = !realNameEnabled ||
        loadedRealName.isNotBlank() || manualName.trim().isNotBlank()
    val canSubmit = contentReady && signatureReady && !submitting

    fun resetForm() {
        content = ""
        contact = ""
        realNameEnabled = false
        manualName = ""
        errorMessage = ""
        submitted = false
        submittedAt = ""
        submittedSummary = ""
    }

    fun submit() {
        if (submitting) return
        val length = FeedbackText.codePointLength(content)
        if (length !in FeedbackRepository.MIN_CONTENT..FeedbackRepository.MAX_CONTENT) {
            errorMessage =
                "反馈内容需在 ${FeedbackRepository.MIN_CONTENT} - ${FeedbackRepository.MAX_CONTENT} 字之间"
            return
        }
        val signature = if (realNameEnabled) {
            loadedRealName.ifBlank { manualName.trim() }
        } else {
            ""
        }
        if (realNameEnabled && signature.isBlank()) {
            errorMessage = "已开启实名发布，请先填写姓名或关闭该开关"
            return
        }

        submitting = true
        errorMessage = ""
        coroutineScope.launch {
            val trimmedContact = contact.trim()
            val anonymous = !realNameEnabled
            feedbackRepo.submit(
                content = content,
                contact = trimmedContact,
                signature = signature,
                anonymous = anonymous
            ).onSuccess { receipt ->
                submittedAt = FeedbackText.formatSubmittedAt(receipt.createdAt)
                val extras = buildList {
                    if (trimmedContact.isNotBlank()) add("联系方式")
                    if (signature.isNotBlank()) add("姓名文本")
                }
                submittedSummary = if (extras.isEmpty()) "" else "、" + extras.joinToString("、")
                submitted = true
                // 已提交，清空表单，避免误重复提交
                content = ""
                contact = ""
                realNameEnabled = false
                manualName = ""
                Toast.makeText(context, "反馈已提交，感谢你的反馈", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                errorMessage = error.message ?: "提交失败，请稍后重试"
            }
            submitting = false
        }
    }

    // 固定顶栏：标题与返回按钮不随内容滚动，列表滚起来后顶栏底色转为不透明
    val listState = rememberLazyListState()
    Box(modifier = modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            // 键盘弹出时列表底部让位，保证输入框与提交按钮可见
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(
            top = pageTopBarTopPadding(),
            bottom = LocalBottomContentInset.current + 32.dp
        )
    ) {
        item { FeedbackIntroCard() }

        when {
            // 偏好尚未读出：先留白，避免门控卡片一闪而过
            prefs == null -> Unit

            !isLoggedIn -> item {
                LoginGateCard(onGoLogin = onGoLogin)
            }

            submitted -> item {
                SubmittedCard(
                    submittedAt = submittedAt,
                    submittedSummary = submittedSummary,
                    onWriteAgain = { resetForm() },
                    onBack = onBack
                )
            }

            else -> item {
                FeedbackFormCard(
                    content = content,
                    onContentChange = { content = it },
                    contentLength = contentLength,
                    contentOverflow = contentOverflow,
                    contact = contact,
                    onContactChange = { contact = FeedbackText.truncate(it, FeedbackRepository.MAX_CONTACT) },
                    realNameEnabled = realNameEnabled,
                    onRealNameChange = { realNameEnabled = it },
                    loadedRealName = loadedRealName,
                    manualName = manualName,
                    onManualNameChange = {
                        manualName = FeedbackText.truncate(it, FeedbackRepository.MAX_SIGNATURE)
                    },
                    errorMessage = errorMessage,
                    submitting = submitting,
                    canSubmit = canSubmit,
                    onSubmit = { submit() }
                )
            }
        }

        item { PrivacyCard() }
    }
        FixedPageTopBar(title = "意见反馈", onBack = onBack)
    }
}

/** 页头说明：只保存用户写下的文字，不带任何学生信息。 */
@Composable
private fun FeedbackIntroCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = "提交使用中遇到的问题、功能建议或数据偏差。反馈只保存你写下的文字，" +
                    "系统不会附带学号、成绩、课表或任何其他学生信息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 19.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 未登录门控：提交前需要先登录教务在线，引导打开重新登录覆盖层。 */
@Composable
private fun LoginGateCard(onGoLogin: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(WarningAmber.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = WarningAmber,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "请先登录教务在线",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "为了确认反馈来自校内使用者，提交前需要先登录教务在线。" +
                        "登录信息仅保存在你自己的设备中，不会随反馈上传到服务器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onGoLogin,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("前往登录")
                }
            }
        }
    }
}

/** 提交成功态：展示提交时间与实际保存的字段，提供「再写一条」与「返回」。 */
@Composable
private fun SubmittedCard(
    submittedAt: String,
    submittedSummary: String,
    onWriteAgain: () -> Unit,
    onBack: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SuccessGreen.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = SuccessGreen,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "反馈已收到，感谢你的反馈",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "提交时间 $submittedAt。已保存的字段：反馈正文、提交时间与阅读状态" +
                        "$submittedSummary。服务器不保存任何可以定位到你本人的身份信息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onWriteAgain, shape = RoundedCornerShape(14.dp)) {
                        Text("再写一条")
                    }
                    TextButton(onClick = onBack) {
                        Text("返回")
                    }
                }
            }
        }
    }
}

/** 反馈表单：内容 + 实名发布 + 联系方式 + 提交。 */
@Composable
private fun FeedbackFormCard(
    content: String,
    onContentChange: (String) -> Unit,
    contentLength: Int,
    contentOverflow: Boolean,
    contact: String,
    onContactChange: (String) -> Unit,
    realNameEnabled: Boolean,
    onRealNameChange: (Boolean) -> Unit,
    loadedRealName: String,
    manualName: String,
    onManualNameChange: (String) -> Unit,
    errorMessage: String,
    submitting: Boolean,
    canSubmit: Boolean,
    onSubmit: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "写下你的反馈",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            // 反馈内容
            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                label = { Text("反馈内容") },
                placeholder = {
                    Text(
                        "请描述遇到的问题、功能建议或数据偏差",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                minLines = 6,
                maxLines = 14,
                isError = contentOverflow,
                shape = AppTextFieldShape,
                supportingText = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "无需标题，直接描述问题或建议即可",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = "$contentLength / ${FeedbackRepository.MAX_CONTENT}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (contentOverflow) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .registerInputField()
            )

            // 实名发布：默认关闭，开启后姓名优先取教务缓存（只读），缺失时手动填写
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = if (realNameEnabled) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                } else {
                    Color.Transparent
                },
                border = BorderStroke(
                    width = 1.dp,
                    color = if (realNameEnabled) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "实名发布",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "默认关闭。开启后本条反馈会带上你的姓名，便于必要时与你核对问题；" +
                                    "每次进入本页都需要重新手动开启。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = realNameEnabled,
                            onCheckedChange = onRealNameChange
                        )
                    }

                    if (realNameEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(12.dp))

                        if (loadedRealName.isNotBlank()) {
                            OutlinedTextField(
                                value = loadedRealName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("姓名") },
                                leadingIcon = {
                                    Icon(Icons.Default.Person, contentDescription = null)
                                },
                                singleLine = true,
                                shape = AppTextFieldShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .registerInputField()
                            )
                        } else {
                            OutlinedTextField(
                                value = manualName,
                                onValueChange = onManualNameChange,
                                label = { Text("姓名") },
                                placeholder = { Text("请输入姓名") },
                                leadingIcon = {
                                    Icon(Icons.Default.Person, contentDescription = null)
                                },
                                singleLine = true,
                                shape = AppTextFieldShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .registerInputField()
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (loadedRealName.isNotBlank()) {
                                "姓名取自当前教务登录缓存，仅随本条反馈一并提交，不会写入任何账号档案。"
                            } else {
                                "暂时无法从教务缓存读取姓名，可手动填写；留空将按匿名提交。"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // 联系方式（选填）
            OutlinedTextField(
                value = contact,
                onValueChange = onContactChange,
                label = { Text("联系方式（选填）") },
                placeholder = { Text("邮箱，手机号，QQ等") },
                leadingIcon = {
                    Icon(Icons.Default.Email, contentDescription = null)
                },
                singleLine = true,
                shape = AppTextFieldShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .registerInputField()
            )

            // 错误提示
            if (errorMessage.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            // 提交
            Text(
                text = "提交即表示你同意我们保存上述文本用于改进产品。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (submitting) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("正在提交…")
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("提交反馈", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** 隐私说明：只保存 / 不保存 / 不关联三组口径。 */
@Composable
private fun PrivacyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "关于隐私",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            PrivacyLine(
                dotColor = SuccessGreen,
                text = "服务器只保存：反馈正文、你选填的联系方式、实名时随反馈提交的姓名文本、提交时间与阅读状态。"
            )
            Spacer(modifier = Modifier.height(8.dp))
            PrivacyLine(
                dotColor = DangerRed,
                text = "服务器不保存：学号、教务内部 ID、成绩、课表、Cookie 等任何教务数据，也不记录来源 IP 与设备信息。"
            )
            Spacer(modifier = Modifier.height(8.dp))
            PrivacyLine(
                dotColor = MaterialTheme.colorScheme.outline,
                text = "反馈不会与你的教务账号建立任何关联，管理端只能阅读文本内容。"
            )
        }
    }
}

@Composable
private fun PrivacyLine(dotColor: Color, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 提交成功提示色（与 Web 端 emerald-500 一致） */
private val SuccessGreen = Emerald500

/** 门控提示色（与 Web 端 amber-500 一致） */
private val WarningAmber = Amber500

/** 隐私说明中的警示色（与 Web 端 rose-500 一致） */
private val DangerRed = Rose500
