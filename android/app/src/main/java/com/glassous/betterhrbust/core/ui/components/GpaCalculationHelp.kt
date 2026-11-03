package com.glassous.betterhrbust.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * GPA 计算说明入口 + 说明弹窗。
 *
 * @param iconOnly true：只在卡片右上角渲染一个问号图标（不占整行高度）；
 *                 false：渲染文字按钮（兼容旧的整行入口）
 * @param tint 图标 / 文字颜色，卡片内一般传次级文字色
 */
@Composable
fun GpaCalculationHelp(
    iconOnly: Boolean = false,
    tint: Color = Color.Unspecified
) {
    var open by remember { mutableStateOf(false) }
    if (iconOnly) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clickable { open = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.HelpOutline,
                contentDescription = "GPA 计算说明",
                tint = if (tint == Color.Unspecified) {
                    androidx.compose.material3.LocalContentColor.current
                } else {
                    tint
                },
                modifier = Modifier.size(18.dp)
            )
        }
    } else {
        TextButton(onClick = { open = true }) { Text("GPA 计算说明") }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        // 弹窗表面与 Web 端 Modal 一致（白 / #1c1e24），而非 M3 默认的浅灰容器色
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("GPA 计算说明") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("五分制：低于60分为0绩点；60分以上按（成绩−50）÷10。优秀4.5、良好3.5、中等2.5、及格1.5、不及格0。\n")
                Text("平均绩点 = Σ（课程绩点×学分）÷Σ学分，0学分及无法折算的成绩不参与加权。\n")
                Text("基础GPA与学业风险按必修课统计。学位绩点包括学业课，另选最高E类一门，再排除该课，从剩余A–E类选最高一门，第二门可以仍为E类。其余通识选修不纳入。\n")
                Text("例：3学分×2.0绩点加2学分×4.0绩点，除以5学分，平均为2.80。学位门槛为1.5，判断使用未舍入结果。\n")
                Text("同一课程合并成绩，优先使用已通过且折算分最高的记录。重复正常记录不增加补考重修门数，一门补考或重修计一次。\n")
                Text("低于70分标红提示，是否及格仍按教务结果。专业选修为X门候选选4门，所需学分读取学校方案。")
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("知道了") } }
    )
}
