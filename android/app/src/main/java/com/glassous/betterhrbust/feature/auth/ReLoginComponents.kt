package com.glassous.betterhrbust.feature.auth

import android.graphics.BitmapFactory
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.datastore.UserPreferencesManager
import com.glassous.betterhrbust.core.ui.AppTextFieldShape
import com.glassous.betterhrbust.core.ui.LocalBottomContentInset
import com.glassous.betterhrbust.core.ui.LocalTopContentInset
import com.glassous.betterhrbust.core.ui.registerInputField
import com.glassous.betterhrbust.core.ui.theme.LocalDarkTheme
import com.glassous.betterhrbust.core.ui.theme.warningBannerColors
import com.glassous.betterhrbust.data.repository.Resource
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * 顶部“登录状态已失效”通知栏，与 Web 端 AppHeader 保持一致。
 *
 * 调用方以浮层方式摆放（对齐顶部、覆盖在内容之上）：出现 / 收起时不会推移其它区域。
 * 支持「忽略」收起，收起后本轮失效不再打扰（下次手动刷新失败仍会重新提示）。
 */
@Composable
fun SessionExpiredBanner(
    onReLoginClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 与 Web 端 AppHeader 的会话提示同一套 amber 语义色
    val warning = warningBannerColors(LocalDarkTheme.current)
    val bannerBg = warning.container
    val bannerBorder = warning.border
    val textColor = warning.content
    val buttonBg = warning.accent
    val interactionSource = remember { MutableInteractionSource() }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            // 悬浮在内容之上：拦截横幅自身区域的触摸，避免误触到下层页面
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {}
            ),
        shape = RoundedCornerShape(20.dp),
        color = bannerBg,
        border = BorderStroke(1.dp, bannerBorder),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(warning.accent.copy(alpha = alpha))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "登录状态已失效",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
            }
            TextButton(
                onClick = onDismiss,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text(
                    text = "忽略",
                    fontSize = 12.sp,
                    color = textColor
                )
            }
            Button(
                onClick = onReLoginClick,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonBg,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = "重新登录",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 重新登录覆盖层：在不退出应用或丢失当前离线浏览状态的前提下，快速重新登录教务系统。
 *
 * 以覆盖层形式从右侧滑入 / 滑出（与二级页面一致），顶部提供返回键，
 * 内容区可滚动并避让输入法。
 */
@Composable
fun ReLoginOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val authRepo = remember { BetterHrbustApp.instance.authRepository }
    val prefsManager = remember { BetterHrbustApp.instance.preferencesManager }
    val prefs by prefsManager.preferencesFlow.collectAsState(initial = null)
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val initialUsername = prefs?.username ?: ""
    var username by remember(initialUsername) { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var rememberPassword by remember { mutableStateOf(false) }
    var prefilled by remember { mutableStateOf(false) }

    var captchaBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isCaptchaLoading by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var captchaErrorMessage by remember { mutableStateOf<String?>(null) }

    fun refreshCaptcha() {
        if (isCaptchaLoading || isLoading) return
        isCaptchaLoading = true
        captchaBytes = null
        captcha = ""
        coroutineScope.launch {
            try {
                val bytes = authRepo.getCaptcha()
                if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) {
                    throw java.io.IOException("验证码图片无效，请重新获取")
                }
                captchaBytes = bytes
                captcha = ""
                if (errorMessage == captchaErrorMessage) errorMessage = null
                captchaErrorMessage = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                captchaErrorMessage = e.message ?: "获取验证码失败，请切换网络后重试"
                errorMessage = captchaErrorMessage
            } finally {
                isCaptchaLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshCaptcha()
    }

    // 回填「记住密码」凭证（仅首次生效，不覆盖用户正在输入的内容）
    LaunchedEffect(prefs) {
        val snapshot = prefs ?: return@LaunchedEffect
        if (prefilled) return@LaunchedEffect
        if (password.isBlank()) {
            val saved = UserPreferencesManager.resolveSavedPassword(username, snapshot.savedCredential)
            if (saved != null) {
                password = saved
                rememberPassword = true
            }
        }
        prefilled = true
    }

    fun submitLogin() {
        if (isLoading || isCaptchaLoading) return
        if (captchaBytes == null) {
            errorMessage = "请先获取验证码"
            return
        }
        if (username.isBlank() || password.isBlank()) {
            errorMessage = "请输入学号和密码"
            return
        }
        if (!captcha.trim().matches(Regex("[0-9A-Za-z]{4}"))) {
            errorMessage = "请输入 4 位验证码"
            return
        }

        isLoading = true
        coroutineScope.launch {
            BetterHrbustApp.instance.syncManager.isSyncing.first { !it }
            authRepo.login(username.trim(), password, captcha.trim(), rememberPassword).collect { resource ->
                when (resource) {
                    is Resource.Loading -> {
                        isLoading = true
                        errorMessage = null
                    }
                    is Resource.Success -> {
                        isLoading = false
                        authRepo.markSessionExpired(false)
                        onDismiss()
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(
                    top = LocalTopContentInset.current + 16.dp,
                    bottom = LocalBottomContentInset.current + 32.dp
                ),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "重新登录",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "教务会话已超时，重新登录后将恢复在线同步",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (errorMessage != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Username
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("学号") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .registerInputField(),
                shape = AppTextFieldShape
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Password
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                        Icon(
                            imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = null
                        )
                    }
                },
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .registerInputField(),
                shape = AppTextFieldShape
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Captcha Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = captcha,
                    onValueChange = { if (it.length <= 4) captcha = it },
                    label = { Text("验证码") },
                    placeholder = { Text("4位数字") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus()
                        submitLogin()
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .registerInputField(),
                    shape = AppTextFieldShape
                )

                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .height(56.dp)
                        .clickable { refreshCaptcha() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isCaptchaLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else if (captchaBytes != null) {
                        val bitmap = remember(captchaBytes) {
                            BitmapFactory.decodeByteArray(captchaBytes, 0, captchaBytes!!.size)
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "验证码",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text("解析失败", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("点击获取", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 记住密码：勾选后账号密码保存在本机，下次打开自动填入；取消勾选立即清除
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = rememberPassword,
                    onCheckedChange = { checked ->
                        rememberPassword = checked
                        if (!checked) coroutineScope.launch { prefsManager.clearSavedCredential() }
                    }
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "记住密码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    focusManager.clearFocus()
                    submitLogin()
                },
                enabled = !isLoading && !isCaptchaLoading && captchaBytes != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("正在重新连接...")
                } else {
                    Text("恢复在线登录", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
