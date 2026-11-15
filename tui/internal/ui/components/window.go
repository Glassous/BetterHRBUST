package components

// Window 是长列表的可见窗口状态。
//
// 列表只渲染可见区间，因此上千条成绩也不会每帧重排。
type Window struct {
	// Cursor 是选中下标。
	Cursor int
	// Offset 是可见区间的起始下标。
	Offset int
	// Size 是可见行数。
	Size int
}

// Clamp 把选中项与偏移量约束在合法范围内。
func (w *Window) Clamp(total int) {
	if total <= 0 {
		w.Cursor = 0
		w.Offset = 0
		return
	}
	if w.Cursor < 0 {
		w.Cursor = 0
	}
	if w.Cursor > total-1 {
		w.Cursor = total - 1
	}
	if w.Size <= 0 {
		w.Offset = 0
		return
	}
	if w.Cursor < w.Offset {
		w.Offset = w.Cursor
	}
	if w.Cursor >= w.Offset+w.Size {
		w.Offset = w.Cursor - w.Size + 1
	}
	maxOffset := total - w.Size
	if maxOffset < 0 {
		maxOffset = 0
	}
	if w.Offset > maxOffset {
		w.Offset = maxOffset
	}
	if w.Offset < 0 {
		w.Offset = 0
	}
}

// Move 移动选中项并同步窗口。
func (w *Window) Move(delta, total int) {
	w.Cursor += delta
	w.Clamp(total)
}

// Home 跳到首项。
func (w *Window) Home(total int) {
	w.Cursor = 0
	w.Clamp(total)
}

// End 跳到末项。
func (w *Window) End(total int) {
	w.Cursor = total - 1
	w.Clamp(total)
}

// PageUp 上翻一屏。
func (w *Window) PageUp(total int) {
	w.Move(-w.page(), total)
}

// PageDown 下翻一屏。
func (w *Window) PageDown(total int) {
	w.Move(w.page(), total)
}

// page 返回一屏的行数增量。
func (w *Window) page() int {
	if w.Size <= 1 {
		return 1
	}
	return w.Size - 1
}

// Range 返回可见区间 [start, end)。
func (w Window) Range(total int) (int, int) {
	start := w.Offset
	if start > total {
		start = total
	}
	end := start + w.Size
	if end > total {
		end = total
	}
	if start < 0 {
		start = 0
	}
	return start, end
}
