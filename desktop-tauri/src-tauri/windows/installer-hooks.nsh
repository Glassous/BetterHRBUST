; BetterHRBUST 安装钩子：把命令行程序接入用户 PATH
;
; 安装包会同时安装 BetterHRBUST.exe（图形界面）与 BetterHRBUSTCLI.exe（命令行）。
; 图形界面有开始菜单快捷方式；命令行需要安装目录在 PATH 里，用户才能直接输入命令名。
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
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  ${If} ${FileExists} "$INSTDIR\BetterHRBUSTCLI.exe"
    nsExec::ExecToLog '"$INSTDIR\BetterHRBUSTCLI.exe" path --uninstall'
  ${EndIf}
!macroend
