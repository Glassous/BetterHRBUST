package com.glassous.betterhrbust.feature.auth

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.glassous.betterhrbust.BetterHrbustApp
import com.glassous.betterhrbust.core.datastore.UserPreferencesManager
import com.glassous.betterhrbust.core.ui.AppTextFieldShape
import com.glassous.betterhrbust.core.ui.registerInputField
import com.glassous.betterhrbust.core.ui.theme.LocalDarkTheme
import com.glassous.betterhrbust.core.ui.theme.warningBannerColors
import com.glassous.betterhrbust.data.repository.Resource
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@Composable
fun AuthScreen(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val authRepo = remember { BetterHrbustApp.instance.authRepository }
    val prefsManager = remember { BetterHrbustApp.instance.preferencesManager }
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val isSessionExpired by authRepo.isSessionExpired.collectAsState()
    val prefs by prefsManager.preferencesFlow.collectAsState(initial = null)

    var username by remember { mutableStateOf("") }
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

    // 回填本地保存的账号与「记住密码」凭证（仅首次生效，不覆盖用户正在输入的内容）
    LaunchedEffect(prefs) {
        val snapshot = prefs ?: return@LaunchedEffect
        if (prefilled) return@LaunchedEffect
        if (snapshot.username.isNotEmpty() && username.isBlank()) {
            username = snapshot.username
        }
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
                        onLoginSuccess()
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            val screenHeight = maxHeight
            // 整体下移：使组件视觉重心更靠下，呈现更舒展优美的留白与黄金分割比例
            val goldenTopOffset = (screenHeight * 0.25f).coerceAtLeast(88.dp)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp)
                    .padding(bottom = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(goldenTopOffset))

                // 无外层卡片背景色，直接渲染在页面底色上
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 440.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    // 图标移至名称左边，与名称一同居左显示；移除副标题
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.School,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = "BetterHRBUST",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // 会话失效提示：不满足"一周节流"条件时此处仍会展示（仅登录页告知）
                    if (isSessionExpired) {
                        val warning = warningBannerColors(LocalDarkTheme.current)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = warning.container,
                            border = BorderStroke(1.dp, warning.border)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "登录状态已失效",
                                    color = warning.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "教务会话已过期，本地缓存的课表 / 成绩 / 考试数据仍可离线查看；重新登录后即可手动刷新最新数据。",
                                    color = warning.content,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

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
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Username
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("学号") },
                        placeholder = { Text("例如 2401234567") },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Person, contentDescription = null)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .registerInputField(),
                        shape = AppTextFieldShape
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Password
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Lock, contentDescription = null)
                        },
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
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .registerInputField(),
                        shape = AppTextFieldShape
                    )

                    Spacer(modifier = Modifier.height(14.dp))

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
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
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
                                .width(112.dp)
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

                    Spacer(modifier = Modifier.height(12.dp))

                    // Submit Button
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            submitLogin()
                        },
                        enabled = !isLoading && !isCaptchaLoading && captchaBytes != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在登录...")
                        } else {
                            Text("登录", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
