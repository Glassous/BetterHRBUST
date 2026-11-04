/**
 * 轻量 Markdown 渲染（零依赖）
 *
 * 用途：把 GitHub Release 的 Markdown 说明渲染成安全 HTML。
 * 设计取舍：
 *  - **先转义、再解析**：原始 HTML 一律转义，因此 Release 描述里的 <script>、
 *    <details> 等只会作为文本显示，不需要额外的 sanitize 库；
 *  - 链接只放行 http(s) / mailto，杜绝 javascript: 之类的伪协议；
 *  - 仅覆盖发布说明里常见的语法：标题、列表、引用、代码块、行内代码、
 *    粗体 / 斜体 / 删除线、链接、图片（渲染为链接）、分割线、表格、段落。
 *
 * 不在支持范围内：嵌套列表、任务列表复选框、脚注、内嵌 HTML、脚注等扩展语法。
 */

const ESCAPE_MAP = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;'
};

/** HTML 转义：所有文本进入渲染管线前都必须先过这一层 */
export function escapeHtml(value) {
  return String(value == null ? '' : value).replace(/[&<>"']/g, ch => ESCAPE_MAP[ch]);
}

/** 只允许 http(s) 与 mailto，其余（含 javascript:、data:）返回空串 */
export function sanitizeUrl(raw) {
  const url = String(raw == null ? '' : raw).trim();
  if (!url) return '';
  return /^(https?:\/\/|mailto:)/i.test(url) ? url : '';
}

function link(url, label) {
  const safe = sanitizeUrl(url);
  if (!safe) return label;
  return `<a href="${safe}" target="_blank" rel="noopener noreferrer">${label}</a>`;
}

/** 行内语法（入参必须是已转义文本） */
export function renderInline(escapedText) {
  let text = String(escapedText || '');

  // 行内代码先挖出来占位，避免其中的 * _ [ ] 被当作强调或链接解析
  const codes = [];
  text = text.replace(/`([^`]+)`/g, (_, code) => {
    codes.push(code);
    return `\u0000${codes.length - 1}\u0000`;
  });

  // 图片：降级为链接（不加载外链图片，避免隐私与版式问题）
  text = text.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, (_, alt, url) => link(url, alt || url));

  // 链接
  text = text.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_, label, url) => link(url, label));

  // 自动链接 <https://...>（转义后尖括号变成 &lt; &gt;）
  text = text.replace(/&lt;((?:https?:\/\/|mailto:)[^\s&]+)&gt;/g, (_, url) => link(url, url));

  // 强调与删除线
  text = text
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/__([^_]+)__/g, '<strong>$1</strong>')
    .replace(/~~([^~]+)~~/g, '<del>$1</del>')
    .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
    .replace(/(^|[^_\w])_([^_\n]+)_/g, '$1<em>$2</em>');

  // 还原行内代码
  text = text.replace(/\u0000(\d+)\u0000/g, (_, idx) => `<code>${codes[Number(idx)] || ''}</code>`);

  return text;
}

const HEADING_RE = /^(#{1,6})\s+(.*)$/;
const HR_RE = /^\s{0,3}(?:-\s*){3,}$|^\s{0,3}(?:\*\s*){3,}$|^\s{0,3}(?:_\s*){3,}$/;
const BULLET_RE = /^\s*[-*+]\s+(.*)$/;
const ORDERED_RE = /^\s*(\d{1,3})[.)]\s+(.*)$/;
const QUOTE_RE = /^\s*>\s?(.*)$/;
const FENCE_RE = /^\s*(```|~~~)\s*([\w+#-]*)\s*$/;
const TABLE_ROW_RE = /^\s*\|(.+)\|\s*$/;
const TABLE_SEPARATOR_RE = /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)+\|?\s*$/;

function splitTableRow(line) {
  const inner = line.trim().replace(/^\|/, '').replace(/\|$/, '');
  return inner.split('|').map(cell => cell.trim());
}

/**
 * 把 Markdown 渲染为 HTML 字符串
 * @param {string} markdown
 * @returns {string} 安全 HTML（可直接 v-html）
 */
export function renderMarkdown(markdown) {
  const source = String(markdown == null ? '' : markdown)
    .replace(/\r\n?/g, '\n')
    // GitHub 描述里常用注释做占位，直接丢弃
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/^\uFEFF/, '');

  const lines = source.split('\n');
  const html = [];
  let index = 0;
  let paragraph = [];

  const flushParagraph = () => {
    if (!paragraph.length) return;
    html.push(`<p>${paragraph.map(line => renderInline(escapeHtml(line.trim()))).join('<br>')}</p>`);
    paragraph = [];
  };

  while (index < lines.length) {
    const line = lines[index];

    // 空行：结束当前段落
    if (!line.trim()) {
      flushParagraph();
      index += 1;
      continue;
    }

    // 代码块
    const fence = line.match(FENCE_RE);
    if (fence) {
      flushParagraph();
      const marker = fence[1];
      const language = fence[2] || '';
      const buffer = [];
      index += 1;
      while (index < lines.length && !lines[index].trim().startsWith(marker)) {
        buffer.push(lines[index]);
        index += 1;
      }
      index += 1; // 跳过结束标记
      const className = language ? ` class="language-${escapeHtml(language)}"` : '';
      html.push(`<pre><code${className}>${escapeHtml(buffer.join('\n'))}</code></pre>`);
      continue;
    }

    // 标题
    const heading = line.match(HEADING_RE);
    if (heading) {
      flushParagraph();
      const level = heading[1].length;
      html.push(`<h${level}>${renderInline(escapeHtml(heading[2].trim()))}</h${level}>`);
      index += 1;
      continue;
    }

    // 分割线
    if (HR_RE.test(line)) {
      flushParagraph();
      html.push('<hr>');
      index += 1;
      continue;
    }

    // 表格：表头 + 分隔行
    if (TABLE_ROW_RE.test(line) && index + 1 < lines.length && TABLE_SEPARATOR_RE.test(lines[index + 1])) {
      flushParagraph();
      const header = splitTableRow(line);
      index += 2;
      const rows = [];
      while (index < lines.length && TABLE_ROW_RE.test(lines[index])) {
        rows.push(splitTableRow(lines[index]));
        index += 1;
      }
      const head = header.map(cell => `<th>${renderInline(escapeHtml(cell))}</th>`).join('');
      const body = rows
        .map(row => `<tr>${header
          .map((_, cellIndex) => `<td>${renderInline(escapeHtml(row[cellIndex] || ''))}</td>`)
          .join('')}</tr>`)
        .join('');
      html.push(`<table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`);
      continue;
    }

    // 引用
    if (QUOTE_RE.test(line)) {
      flushParagraph();
      const buffer = [];
      while (index < lines.length && QUOTE_RE.test(lines[index])) {
        buffer.push(lines[index].replace(QUOTE_RE, '$1'));
        index += 1;
      }
      html.push(`<blockquote><p>${buffer.map(item => renderInline(escapeHtml(item.trim()))).join('<br>')}</p></blockquote>`);
      continue;
    }

    // 无序列表
    if (BULLET_RE.test(line)) {
      flushParagraph();
      const items = [];
      while (index < lines.length && BULLET_RE.test(lines[index])) {
        items.push(lines[index].replace(BULLET_RE, '$1'));
        index += 1;
      }
      html.push(`<ul>${items.map(item => `<li>${renderInline(escapeHtml(item.trim()))}</li>`).join('')}</ul>`);
      continue;
    }

    // 有序列表
    if (ORDERED_RE.test(line)) {
      flushParagraph();
      const items = [];
      let start = 1;
      while (index < lines.length && ORDERED_RE.test(lines[index])) {
        const matched = lines[index].match(ORDERED_RE);
        if (!items.length) start = Number(matched[1]) || 1;
        items.push(matched[2]);
        index += 1;
      }
      const startAttr = start !== 1 ? ` start="${start}"` : '';
      html.push(`<ol${startAttr}>${items.map(item => `<li>${renderInline(escapeHtml(item.trim()))}</li>`).join('')}</ol>`);
      continue;
    }

    paragraph.push(line);
    index += 1;
  }

  flushParagraph();
  return html.join('\n');
}
