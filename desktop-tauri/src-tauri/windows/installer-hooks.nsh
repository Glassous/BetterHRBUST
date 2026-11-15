; BetterHRBUST 安装钩子：把命令行程序接入用户 PATH
;
; 安装包会同时安装三个可执行文件：
;   BetterHRBUST.exe      图形界面（带开始菜单快捷方式）
;   BetterHRBUSTCLI.exe   命令行（查询桌面端已同步的本地缓存）
;   BetterHRBUSTTUI.exe  终端界面（Go 编写，同样只读本地缓存）
;
; 三者同在安装目录，因此 PATH 只需写入安装目录一次：追加后
; `BetterHRBUSTCLI ...` 与 `BetterHRBUSTTUI` 都能直接按名字调用，
; 不需要为终端界面单独维护第二段 PATH。
;
; 为什么不在 NSIS 脚本里直接读写注册表：
; NSIS 的字符串上限默认 1024 字符，而真实用户 PATH 常常更长（本机实测 1087）。
; 此时 ReadRegStr 会失败并返回空串，紧接着写入就把整个 PATH **覆盖成只剩一段**
; （已在本地复现，破坏性很强）。因此改由 BetterHRBUSTCLI.exe 自己处理：
;   - 直接调用注册表 API，长度上限 32767；
;   - 逐段比较、只增删自己那一段，保留原有 REG_EXPAND_SZ 语义；
;   - 写入后广播 WM_SETTINGCHANGE，让新开的终端立即生效。
;
; 安装 / 卸载都只操作 HKCU（currentUser 安装模式，无需管理员）。
; 卸载用 PREUNINSTALL：那时文件还没被删除，命令行程序仍可执行。

!include "LogicLib.nsh"

!macro NSIS_HOOK_POSTINSTALL
  ${If} ${FileExists} "$INSTDIR\BetterHRBUSTCLI.exe"
    nsExec::ExecToLog '"$INSTDIR\BetterHRBUSTCLI.exe" path --install'
  ${EndIf}
  ; 打包回归防线：终端界面漏进包时提示一行，而不是让用户以为命令名不存在
  ${IfNot} ${FileExists} "$INSTDIR\BetterHRBUSTTUI.exe"
    DetailPrint "提示：本次安装包未包含终端界面 BetterHRBUSTTUI.exe"
  ${EndIf}
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  ${If} ${FileExists} "$INSTDIR\BetterHRBUSTCLI.exe"
    nsExec::ExecToLog '"$INSTDIR\BetterHRBUSTCLI.exe" path --uninstall'
  ${EndIf}
!macroend
