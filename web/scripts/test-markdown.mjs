/**
 * 版本更新日志 · Markdown 渲染单元测试
 *
 * markdown.js 是纯函数、不依赖 DOM，可直接在 Node 中验证：
 * HTML 转义、伪协议过滤、标题 / 列表 / 代码块 / 表格等块级语法。
 *
 * 运行：node scripts/test-markdown.mjs
 */

import assert from 'node:assert/strict';
import { escapeHtml, renderInline, renderMarkdown, sanitizeUrl } from '../src/utils/markdown.js';

let passed = 0;

function test(name, fn) {
  try {
    fn();
    passed += 1;
    console.log(`  ✓ ${name}`);
  } catch (error) {
    console.error(`  ✗ ${name}`);
    throw error;
  }
}

console.log('[markdown] 安全边界');

test('原始 HTML 一律转义，不会执行', () => {
  const html = renderMarkdown('<script>alert(1)</script>');
  assert.ok(html.includes('&lt;script&gt;'));
  assert.ok(!html.includes('<script>'));
});

test('伪协议链接被丢弃，只保留文字', () => {
  assert.equal(sanitizeUrl('javascript:alert(1)'), '');
  assert.equal(sanitizeUrl('data:text/html,1'), '');
  const html = renderMarkdown('[点我](javascript:alert(1))');
  assert.ok(html.includes('点我'));
  assert.ok(!html.includes('href'));
});

test('正常链接补全 target 与 rel', () => {
  const html = renderMarkdown('[发布页](https://github.com/Glassous/BetterHRBUST/releases)');
  assert.ok(html.includes('href="https://github.com/Glassous/BetterHRBUST/releases"'));
  assert.ok(html.includes('rel="noopener noreferrer"'));
});

test('HTML 注释被移除', () => {
  assert.equal(renderMarkdown('正文<!-- 占位 -->').trim(), '<p>正文</p>');
});

console.log('[markdown] 块级语法');

test('标题分级', () => {
  assert.equal(renderMarkdown('# 一级'), '<h1>一级</h1>');
  assert.equal(renderMarkdown('### 三级'), '<h3>三级</h3>');
});

test('无序列表合并为一组', () => {
  const html = renderMarkdown('- 第一项\n- 第二项\n* 第三项');
  assert.equal(html, '<ul><li>第一项</li><li>第二项</li><li>第三项</li></ul>');
});

test('有序列表保留起始序号', () => {
  const html = renderMarkdown('3. 第三\n4. 第四');
  assert.equal(html, '<ol start="3"><li>第三</li><li>第四</li></ol>');
});

test('代码块保留换行并转义', () => {
  const html = renderMarkdown('```js\nif (a < b) {\n  run();\n}\n```');
  assert.ok(html.includes('<pre><code class="language-js">'));
  assert.ok(html.includes('a &lt; b'));
  assert.ok(html.includes('\n  run();'));
});

test('引用与分割线', () => {
  assert.equal(renderMarkdown('> 注意事项'), '<blockquote><p>注意事项</p></blockquote>');
  assert.equal(renderMarkdown('---'), '<hr>');
});

test('表格渲染为 thead/tbody', () => {
  const html = renderMarkdown('| 端 | 版本 |\n| --- | --- |\n| Web | 1.3.1 |');
  assert.ok(html.includes('<th>端</th>'));
  assert.ok(html.includes('<td>1.3.1</td>'));
});

test('段落保留软换行为 <br>', () => {
  assert.equal(renderMarkdown('第一行\n第二行'), '<p>第一行<br>第二行</p>');
});

console.log('[markdown] 行内语法');

test('粗体 / 斜体 / 删除线 / 行内代码', () => {
  assert.equal(renderInline('**粗**'), '<strong>粗</strong>');
  assert.equal(renderInline('*斜*'), '<em>斜</em>');
  assert.equal(renderInline('~~删~~'), '<del>删</del>');
  assert.equal(renderInline('`code`'), '<code>code</code>');
});

test('行内代码里的星号不被当作强调', () => {
  const html = renderInline(escapeHtml('`a*b*c`'));
  assert.equal(html, '<code>a*b*c</code>');
});

test('强调中嵌套行内代码', () => {
  const html = renderMarkdown('**重要 `code` 说明**');
  assert.ok(html.includes('<strong>'));
  assert.ok(html.includes('<code>code</code>'));
});

test('图片降级为链接', () => {
  const html = renderMarkdown('![截图](https://example.com/a.png)');
  assert.ok(html.includes('href="https://example.com/a.png"'));
  assert.ok(html.includes('>截图</a>'));
});

console.log(`\n全部通过：${passed} 项`);
