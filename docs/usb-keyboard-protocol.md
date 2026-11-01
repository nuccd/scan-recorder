# USB 输入协议 v1

手机只在前台扫码主页、模式开启时监听 `127.0.0.1:28461`。需要 Android 普通 `INTERNET` 权限以使用本地 socket，不使用外部网络。电脑通过明确设备的 `adb -s <serial> forward tcp:0 tcp:28461` 分配独立端口；退出时只删除本程序创建的转发，不重启共享 ADB 服务。

协议使用 ASCII 行，末尾 LF，扫码原文以 UTF-8 字节的小写十六进制编码。十六进制仅用于传输分帧，电脑键盘输入仍是解码后的原文。传输 ID 不输入电脑，不代替业务序号。

```text
手机 → SCANRECORDER/1
电脑 → READY/1
手机 → PING
电脑 → PONG ACTIVE 或 PONG PAUSED
手机 → DATA <随机 UUID> <UTF-8 十六进制>
电脑 → RESULT <同一 UUID> <状态>
```

状态：`OK` 为 SendInput 接受全部按键；`PAUSED`、`NO_TARGET` 为未输入；`INVALID` 为拒绝内容；`PARTIAL` 或不匹配/丢失回执为待核对。`OK` 不证明目标应用保存或提交了数据。

手机先持久保存原文和未发送状态，开始传输前持久标记正在发送；重开时将未结束的发送改为待核对。网络失败不会回滚本地记录，也不自动重试。手动补发产生新的 ID，可能重复，需用户确认。电脑同一进程缓存最近 1024 个 ID 的结果，相同 ID 不再生成键盘事件；同 ID 不同内容被拒绝。该缓存不落盘，不承诺跨进程重启的恰好一次输入。

电脑对 UTF-8、单行规则和大小进行再次校验。用 Unicode 键盘事件输入原文，再追加一对 Enter 按下/释放事件；不使用剪贴板。接收程序自身作为前台窗口、没有前台窗口或修饰键被按住时不输入。

测试：

- `KeyboardProtocolTest`：原文、前导零、Unicode、控制字符、边界和回执匹配。
- `KeyboardStoreTest`：v1 数据库迁移、重复规则、状态恢复、编辑和撤销。
- `KeyboardBridgeTest`：断线不回放、回执丢失、暂停状态，使用隔离的本地接收端，不生成键盘事件。
- `windows-receiver/tests/protocol_test.cpp`：桌面解析、按键事件和 ID 去重；Windows 构建脚本自动执行。
- `windows-receiver/tests/adb_process_test.cpp`：自有假 ADB 验证超时、取消、错误和输出保留、管道生命周期、设备搜索、日志上限及本进程资源采样；构建脚本自动执行，不启动共享 ADB 或生成键盘输入。
- `windows-receiver/tests/receiver_ui_test.cpp`：专用窗口验证启动默认开启、手动暂停、实时日志、超时恢复、错误和未授权提示、设备列表及资源显示。构建脚本只编译；手动运行时传入接收程序、假 ADB 测试 EXE，可选第三个参数为真实 ADB 路径。验证默认勾选后会立即暂停，再开始刷新，不生成键盘输入；超时场景约等待 12 秒。
- `KeyboardDesktopIntegrationTest` 默认跳过，只在显式参数下运行：`desktop_input=transport-only` 只验证真实接收程序的暂停连接，不输入文字；`desktop_input=owned-target` 必须配合 `desktop_target.cpp` 的专用测试窗口。测试窗口未取得前台焦点时返回 77 跳过，不向其他窗口输入。

本协议不以 logcat 作为实时传输通道，原有 `SCAN_EXPORT` 批量日志导出仍独立可用。
