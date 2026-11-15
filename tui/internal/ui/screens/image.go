package screens

import (
	"bytes"
	"fmt"
	"image"
	// 解码器必须显式注册：`image.Decode` 只认已注册的格式，
	// 少了这三行，教务返回的 JPEG / PNG 验证码会一律解不出来。
	_ "image/gif"
	_ "image/jpeg"
	_ "image/png"
	"strings"
)

// 图像渲染。
//
// 验证码必须在终端里看得见——这是"终端也能独立登录"的前提。
// 终端没有图片协议可用时，用「上下半块 + 真彩色」把每个字符画成 1×2 像素，
// 兼容性最好（Windows Terminal / iTerm / 绝大多数等宽终端都支持）。

// decodeImage 解码验证码，返回图像与格式名（png / jpeg / gif）。
func decodeImage(data []byte) (image.Image, string, error) {
	return image.Decode(bytes.NewReader(data))
}

// renderImage 把图像渲染成若干行真彩色字符串。
//
// cols 是可用列数；图像会按整数倍缩小以适配，避免糊成一团。
func renderImage(img image.Image, cols int) []string {
	bounds := img.Bounds()
	width := bounds.Dx()
	height := bounds.Dy()
	if width <= 0 || height <= 0 || cols <= 0 {
		return nil
	}

	// 每个字符 1 像素宽、2 像素高；先按列数定缩放倍率（至少 1）
	scale := 1
	for (width+scale-1)/scale > cols {
		scale++
	}
	cellWidth := width / scale
	if cellWidth <= 0 {
		cellWidth = 1
	}
	cellHeight := height / scale
	if cellHeight <= 0 {
		cellHeight = 1
	}

	lines := make([]string, 0, (cellHeight+1)/2)
	for row := 0; row+1 < cellHeight || row < cellHeight; row += 2 {
		var builder strings.Builder
		for column := 0; column < cellWidth; column++ {
			top := averageColor(img, bounds.Min.X+column*scale, bounds.Min.Y+row*scale, scale)
			bottom := top
			if row+1 < cellHeight {
				bottom = averageColor(img, bounds.Min.X+column*scale, bounds.Min.Y+(row+1)*scale, scale)
			}
			builder.WriteString(fmt.Sprintf("\x1b[38;2;%d;%d;%dm\x1b[48;2;%d;%d;%dm▀",
				top.r, top.g, top.b, bottom.r, bottom.g, bottom.b))
		}
		builder.WriteString("\x1b[0m")
		lines = append(lines, builder.String())
	}
	return lines
}

// upscale 用最近邻放大图片。
//
// 验证码本身可能只有 60×20 像素，铺满整宽后字符仍然很小。最近邻放大
// 会把每个原像素变成 factor×factor 的方块：不引入插值模糊，笔画反而更
// 容易辨认——这比双线性更适合"看清几个数字"这个目标。
func upscale(src image.Image, factor int) image.Image {
	if factor <= 1 {
		return src
	}
	bounds := src.Bounds()
	width := bounds.Dx()
	height := bounds.Dy()
	if width <= 0 || height <= 0 {
		return src
	}
	dst := image.NewRGBA(image.Rect(0, 0, width*factor, height*factor))
	for y := 0; y < height*factor; y++ {
		for x := 0; x < width*factor; x++ {
			dst.Set(x, y, src.At(bounds.Min.X+x/factor, bounds.Min.Y+y/factor))
		}
	}
	return dst
}

// rgb 是 8 位颜色。
type rgb struct{ r, g, b uint8 }

// averageColor 取一块像素的平均颜色。
func averageColor(img image.Image, x, y, size int) rgb {
	bounds := img.Bounds()
	var sumR, sumG, sumB, count uint64
	for dy := 0; dy < size; dy++ {
		for dx := 0; dx < size; dx++ {
			px := x + dx
			py := y + dy
			if px >= bounds.Max.X || py >= bounds.Max.Y {
				continue
			}
			// 先按 16 位色取平均，最后再折回 8 位，避免溢出
			r, g, b, _ := img.At(px, py).RGBA()
			sumR += uint64(r >> 8)
			sumG += uint64(g >> 8)
			sumB += uint64(b >> 8)
			count++
		}
	}
	if count == 0 {
		return rgb{}
	}
	return rgb{
		r: uint8(sumR / count),
		g: uint8(sumG / count),
		b: uint8(sumB / count),
	}
}
