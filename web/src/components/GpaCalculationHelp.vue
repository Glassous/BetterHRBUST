<template>
  <details class="text-[13px] text-[var(--ink-secondary)] max-w-2xl">
    <summary class="cursor-pointer select-none font-medium text-[var(--accent)] hover:underline underline-offset-4 inline-flex items-center gap-1">GPA 计算说明</summary>
    <div class="mt-3 space-y-2.5 leading-relaxed rounded-[var(--radius-panel)] border border-[var(--border-hairline)] bg-[var(--bg-subtle)] p-4.5 text-[var(--ink-secondary)]">
      <p><strong class="text-[var(--ink-primary)]">五分制换算：</strong>百分制低于 60 分为 0 绩点；60 分及以上按（成绩 − 50）÷ 10，最高 5.0。五级制优秀 4.5、良好 3.5、中等 2.5、及格 1.5、不及格 0。</p>
      <p><strong class="text-[var(--ink-primary)]">加权公式：</strong>平均学分绩点 = Σ（课程绩点 × 学分）÷ Σ学分。0 学分和无法折算的成绩不参与加权。</p>
      <p><strong class="text-[var(--ink-primary)]">基础 GPA：</strong>当前 GPA、均分、优秀率和学业风险按必修课统计；培养方案已获学分则按已通过课程归集。</p>
      <p><strong class="text-[var(--ink-primary)]">学位绩点：</strong>学业课（包含专业选修）加两门通识选修。先选最高 E 类一门，排除该课后，从剩余 A–E 类选最高一门；第二门可以仍是 E 类。其余通识选修不计入这个分子和分母。</p>
      <p><strong class="text-[var(--ink-primary)]">例子：</strong>一门 3 学分课程绩点 2.0，另一门 2 学分课程绩点 4.0，平均绩点为（3 × 2 + 2 × 4）÷ 5 = 2.80。学位绩点门槛为 1.5，达标判断使用未舍入的计算结果。</p>
      <p><strong class="text-[var(--ink-primary)]">补考与重修：</strong>同一课程先合并，优先使用已通过且折算分最高的记录；完全重复的正常记录不会增加补考重修门数，同一门补考或重修只统计一次。</p>
      <p><strong class="text-[var(--ink-primary)]">颜色与学分：</strong>低于 70 分显示红色提示，及格状态仍以教务为准。专业选修显示 X 门候选选 4 门，学分要求读取学校课组数据。推免显示的是部分学业指标，最终条件以本学院当年细则为准。</p>
    </div>
  </details>
</template>
