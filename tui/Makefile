# BetterHRBUST 终端界面 —— 构建与测试
#
# 全部目标在 tui/ 目录下执行。产物在 bin/，不会污染仓库其他部分。

BINARY   := betterhrbust-tui
VERSION  ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
LDFLAGS  := -s -w -X github.com/Glassous/BetterHRBUST/tui/internal/version.Version=$(VERSION)
GOFLAGS  := -trimpath
# 纯 Go 静态二进制：不依赖 libc，才能在任意 Linux 发行版与 Alpine 容器里直接跑。
export CGO_ENABLED := 0

.PHONY: all build test check fmt vet snapshot demo clean release-matrix help

all: check build

## build: 构建本机二进制到 bin/
build:
	@mkdir -p bin
	go build $(GOFLAGS) -ldflags "$(LDFLAGS)" -o bin/$(BINARY) ./cmd/betterhrbust-tui
	@echo "已生成 bin/$(BINARY)（版本 $(VERSION)）"

## test: 运行全部测试（含 parity 与视图 golden）
test:
	go test ./...

## check: gofmt + go vet + go test，与 CI 的门禁一致
check: fmt vet test

## fmt: 检查格式（不通过时列出文件并失败，不自动改写）
fmt:
	@unformatted=$$(gofmt -l .); \
	if [ -n "$$unformatted" ]; then \
		echo "以下文件未格式化，请执行 gofmt -w . ："; \
		echo "$$unformatted"; \
		exit 1; \
	fi
	@echo "gofmt 通过"

## vet: 静态检查
vet:
	go vet ./...

## demo: 用共享夹具启动界面（不需要真实登录，适合验证排版与交互）
demo: build
	@demo_dir="$$(mktemp -d)"; \
	cp ../shared/parity/fixtures/storage-full.json "$$demo_dir/storage.json"; \
	echo "夹具数据目录：$$demo_dir"; \
	echo "固定答案：GPA 2.40 · 加权 74.00 · 已获 3/5 学分 · 挂科 1 门 · 学位绩点 3.00 · 第 11 教学周"; \
	./bin/$(BINARY) --data-dir "$$demo_dir"

## snapshot: 重新生成视图 golden（改动排版后执行，并 review diff）
snapshot:
	go test ./internal/ui/screens -update
	@echo "已更新内部视图快照，请用 git diff 复核"

## release-matrix: 交叉编译五个目标到 dist/
release-matrix:
	@mkdir -p dist
	@for target in windows/amd64 linux/amd64 linux/arm64 darwin/amd64 darwin/arm64; do \
		os=$${target%/*}; arch=$${target#*/}; \
		out=dist/BetterHRBUST-$(VERSION)-tui-$$os-$$arch; \
		if [ "$$os" = "windows" ]; then out=$$out.exe; fi; \
		echo "构建 $$os/$$arch → $$out"; \
		GOOS=$$os GOARCH=$$arch go build $(GOFLAGS) -ldflags "$(LDFLAGS)" \
			-o $$out ./cmd/betterhrbust-tui || exit 1; \
	done
	@echo "产物在 dist/"

## clean: 清理构建产物
clean:
	rm -rf bin dist

## help: 显示可用目标
help:
	@grep -E '^## ' $(MAKEFILE_LIST) | sed 's/^## /  /'
