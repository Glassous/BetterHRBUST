package main

import "testing"

// --base-url 与既有的布尔开关 --base 只差一个后缀，必须能正确区分。
func TestParseBaseURLFlag(t *testing.T) {
	flags, err := parseFlags([]string{"--base-url", "jwzx.fiacloud.top", "--json", "summary"})
	if err != nil {
		t.Fatalf("解析失败：%v", err)
	}
	if flags.baseURL != "jwzx.fiacloud.top" {
		t.Fatalf("--base-url 取值不正确：%q", flags.baseURL)
	}
	if flags.options.Base {
		t.Fatal("--base-url 不应打开小节课表开关")
	}

	// 支持 --k=v 形式
	flags, err = parseFlags([]string{"--base-url=https://jwzx.example.edu.cn", "--json", "summary"})
	if err != nil {
		t.Fatalf("解析失败：%v", err)
	}
	if flags.baseURL != "https://jwzx.example.edu.cn" {
		t.Fatalf("--base-url= 形式取值不正确：%q", flags.baseURL)
	}

	// --base 仍是布尔开关
	flags, err = parseFlags([]string{"--base", "--json", "timetable"})
	if err != nil {
		t.Fatalf("解析失败：%v", err)
	}
	if !flags.options.Base || flags.baseURL != "" {
		t.Fatalf("--base 的语义被破坏：%+v", flags)
	}
}

func TestParseBaseURLRejectsInvalid(t *testing.T) {
	for _, args := range [][]string{
		{"--base-url", "ftp://jwzx.example.edu.cn"},
		{"--base-url", "http://user:pass@jwzx.example.edu.cn"},
		{"--base-url"},
	} {
		if _, err := parseFlags(append(args, "--json", "summary")); err == nil {
			t.Fatalf("%v 应当被拒绝", args)
		}
	}
}
