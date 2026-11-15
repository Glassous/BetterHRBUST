package campus

import (
	"bytes"
	"strings"

	"golang.org/x/net/html"
)

// 极简 DOM 层。
//
// 教务页面的解析逻辑在图形界面是 `web/src/services/academic/parser.js`，
// 那里用 DOMParser + querySelector。终端不引第三方 HTML 选择器库，
// 只在这里实现该文件实际用到的子集：标签 / #id / .class / [attr] /
// [attr="v"] / [attr*="v"]，以及后代组合（空格分隔）。语义与浏览器一致，
// 两边解析同一份页面必须得到同一份结构。

// selAttr 是属性条件。
type selAttr struct {
	key string
	// op 为空表示只要求属性存在；"=" 精确匹配；"*=" 包含匹配。
	op  string
	val string
}

// selStep 是选择器中的一步。
type selStep struct {
	tag   string
	id    string
	class []string
	attrs []selAttr
}

// compileSelectors 把 "a, b.c" 编译成多组步骤。
func compileSelectors(list string) [][]selStep {
	groups := make([][]selStep, 0, 4)
	for _, chunk := range strings.Split(list, ",") {
		steps := compileSteps(chunk)
		if len(steps) > 0 {
			groups = append(groups, steps)
		}
	}
	return groups
}

// compileSteps 把 "table.a tr.b" 编译成两步。
func compileSteps(selector string) []selStep {
	fields := strings.Fields(selector)
	steps := make([]selStep, 0, len(fields))
	for _, field := range fields {
		if step, ok := compileStep(field); ok {
			steps = append(steps, step)
		}
	}
	return steps
}

// compileStep 解析单个复合选择器片段。
func compileStep(fragment string) (selStep, bool) {
	var step selStep
	rest := fragment
	for rest != "" {
		switch rest[0] {
		case '#':
			end := indexOfAny(rest[1:], "#.[")
			if end < 0 {
				end = len(rest) - 1
			}
			step.id = rest[1 : 1+end]
			rest = rest[1+end:]
		case '.':
			end := indexOfAny(rest[1:], "#.[")
			if end < 0 {
				end = len(rest) - 1
			}
			step.class = append(step.class, rest[1:1+end])
			rest = rest[1+end:]
		case '[':
			end := strings.IndexByte(rest, ']')
			if end < 0 {
				return step, false
			}
			if attr, ok := compileAttr(rest[1:end]); ok {
				step.attrs = append(step.attrs, attr)
			}
			rest = rest[end+1:]
		default:
			end := indexOfAny(rest, "#.[")
			if end < 0 {
				end = len(rest)
			}
			step.tag = strings.ToLower(rest[:end])
			rest = rest[end:]
		}
	}
	return step, step.tag != "" || step.id != "" || len(step.class) > 0 || len(step.attrs) > 0
}

// compileAttr 解析 [key]、[key=v]、[key*=v]（值可带引号）。
func compileAttr(body string) (selAttr, bool) {
	body = strings.TrimSpace(body)
	if body == "" {
		return selAttr{}, false
	}
	key := body
	op := ""
	value := ""
	if index := strings.Index(body, "*="); index >= 0 {
		key, op, value = body[:index], "*=", body[index+2:]
	} else if index := strings.IndexByte(body, '='); index >= 0 {
		key, op, value = body[:index], "=", body[index+1:]
	}
	key = strings.ToLower(strings.TrimSpace(key))
	value = strings.Trim(strings.TrimSpace(value), `"'`)
	if key == "" {
		return selAttr{}, false
	}
	return selAttr{key: key, op: op, val: value}, true
}

// indexOfAny 返回任一字节首次出现的位置，未出现返回 -1。
func indexOfAny(text, chars string) int {
	for index := 0; index < len(text); index++ {
		if strings.IndexByte(chars, text[index]) >= 0 {
			return index
		}
	}
	return -1
}

// queryFirst 返回文档顺序上第一个匹配任一选择器组的元素。
func queryFirst(root *html.Node, selectorList string) *html.Node {
	groups := compileSelectors(selectorList)
	if root == nil || len(groups) == 0 {
		return nil
	}
	var found *html.Node
	walkElements(root, func(node *html.Node) bool {
		if matchesAny(node, groups) {
			found = node
			return false
		}
		return true
	})
	return found
}

// queryAll 返回全部匹配任一选择器组的元素（文档顺序）。
func queryAll(root *html.Node, selectorList string) []*html.Node {
	groups := compileSelectors(selectorList)
	if root == nil || len(groups) == 0 {
		return nil
	}
	var out []*html.Node
	walkElements(root, func(node *html.Node) bool {
		if matchesAny(node, groups) {
			out = append(out, node)
		}
		return true
	})
	return out
}

// matchesAny 判断节点是否匹配任意一组选择器。
func matchesAny(node *html.Node, groups [][]selStep) bool {
	for _, steps := range groups {
		if matchSteps(node, steps) {
			return true
		}
	}
	return false
}

// matchSteps 判断节点及其祖先是否满足一组后代组合。
func matchSteps(node *html.Node, steps []selStep) bool {
	if len(steps) == 0 || !matchStep(node, steps[len(steps)-1]) {
		return false
	}
	pending := len(steps) - 2
	if pending < 0 {
		return true
	}
	for parent := node.Parent; parent != nil; parent = parent.Parent {
		if parent.Type != html.ElementNode {
			continue
		}
		if matchStep(parent, steps[pending]) {
			pending--
			if pending < 0 {
				return true
			}
		}
	}
	return false
}

// matchStep 判断单个元素是否满足一步选择器。
func matchStep(node *html.Node, step selStep) bool {
	if node == nil || node.Type != html.ElementNode {
		return false
	}
	if step.tag != "" && step.tag != "*" && !strings.EqualFold(node.Data, step.tag) {
		return false
	}
	if step.id != "" && attrOf(node, "id") != step.id {
		return false
	}
	for _, class := range step.class {
		if !hasClass(node, class) {
			return false
		}
	}
	for _, attr := range step.attrs {
		value, ok := attrValue(node, attr.key)
		if !ok {
			return false
		}
		switch attr.op {
		case "=":
			if value != attr.val {
				return false
			}
		case "*=":
			if !strings.Contains(value, attr.val) {
				return false
			}
		}
	}
	return true
}

// walkElements 深度优先遍历元素节点；visit 返回 false 时提前结束。
func walkElements(node *html.Node, visit func(*html.Node) bool) bool {
	for child := node.FirstChild; child != nil; child = child.NextSibling {
		if child.Type == html.ElementNode {
			if !visit(child) {
				return false
			}
		}
		if !walkElements(child, visit) {
			return false
		}
	}
	return true
}

// attrOf 返回属性值，缺失为空串。
func attrOf(node *html.Node, key string) string {
	value, _ := attrValue(node, key)
	return value
}

// attrValue 返回属性值与是否存在。
func attrValue(node *html.Node, key string) (string, bool) {
	for _, attr := range node.Attr {
		if strings.EqualFold(attr.Key, key) {
			return attr.Val, true
		}
	}
	return "", false
}

// hasClass 判断元素是否带某个 class。
func hasClass(node *html.Node, class string) bool {
	for _, name := range strings.Fields(attrOf(node, "class")) {
		if name == class {
			return true
		}
	}
	return false
}

// nodeText 等价于 DOM 的 textContent：拼接全部后代文本（含 script/style）。
func nodeText(node *html.Node) string {
	if node == nil {
		return ""
	}
	if node.Type == html.TextNode {
		return node.Data
	}
	var builder strings.Builder
	var collect func(*html.Node)
	collect = func(current *html.Node) {
		for child := current.FirstChild; child != nil; child = child.NextSibling {
			switch child.Type {
			case html.TextNode:
				builder.WriteString(child.Data)
			case html.ElementNode:
				collect(child)
			}
		}
	}
	collect(node)
	return builder.String()
}

// nodeInnerHTML 等价于 DOM 的 innerHTML（用于按 <br> 切分课表单元格）。
func nodeInnerHTML(node *html.Node) string {
	if node == nil {
		return ""
	}
	var buffer bytes.Buffer
	for child := node.FirstChild; child != nil; child = child.NextSibling {
		if err := html.Render(&buffer, child); err != nil {
			continue
		}
	}
	return buffer.String()
}

// elementChildren 返回直接子元素。
func elementChildren(node *html.Node) []*html.Node {
	if node == nil {
		return nil
	}
	var out []*html.Node
	for child := node.FirstChild; child != nil; child = child.NextSibling {
		if child.Type == html.ElementNode {
			out = append(out, child)
		}
	}
	return out
}

// childElementsByTag 返回直接子元素中指定标签的节点。
func childElementsByTag(node *html.Node, tag string) []*html.Node {
	var out []*html.Node
	for _, child := range elementChildren(node) {
		if strings.EqualFold(child.Data, tag) {
			out = append(out, child)
		}
	}
	return out
}

// rowCells 返回表格行的直接数据单元格（只算 td）。
//
// 只算 td 是刻意的：parser.js 全部用 `row.querySelectorAll('td')`，
// 表头行（th）不应被当成数据行。
func rowCells(row *html.Node) []*html.Node {
	var out []*html.Node
	for _, child := range elementChildren(row) {
		if strings.EqualFold(child.Data, "td") {
			out = append(out, child)
		}
	}
	return out
}

// tableRows 返回表格中的全部行（不含嵌套表格的行，与 HTMLTableElement.rows 一致）。
func tableRows(table *html.Node) []*html.Node {
	var out []*html.Node
	var visit func(*html.Node, int)
	visit = func(node *html.Node, depth int) {
		for _, child := range elementChildren(node) {
			switch strings.ToLower(child.Data) {
			case "tr":
				out = append(out, child)
			case "table":
				// 嵌套表格属于单元格内容，不参与外层 rows
				continue
			default:
				visit(child, depth+1)
			}
		}
	}
	visit(table, 0)
	return out
}

// trimmedText 返回去首尾空白的 textContent。
func trimmedText(node *html.Node) string {
	return strings.TrimSpace(nodeText(node))
}
