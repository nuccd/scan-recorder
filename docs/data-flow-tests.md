# 保存与 TXT 导出自动化测试

本组测试保护“待确认内容 → 已录入记录”和“批次快照 → TXT 文件”两个流程。在 Android 模拟器运行，不启动相机，不测试手机连接、ADB 命令处理或 USB 发送，也不产生电脑键盘输入。

## 覆盖行为

本轮新增 7 个 instrumentation 测试：

| 测试类 | 新增场景 | 关键断言 |
| --- | --- | --- |
| `RecorderReviewFlowTest` | 确认保存失败后重试 | SQLite 拒绝写入时，待确认内容、保存状态与数据库不变；显示存储错误；解除故障后只新增一条 |
| `RecorderReviewFlowTest` | 待确认内容恢复后确认 | 从原 ViewModel 的实际 SavedState 值重建；允许重复时连点确认仍只写一条，原批次和原文准确 |
| `RecorderExportFlowTest` | 准备快照后编辑、添加与删除记录 | 导出文件逐字节对应准备时的原顺序和原文，当前数据库不被导出改动 |
| `RecorderExportFlowTest` | 文件目标返回前重建 ViewModel | 已准备的文件快照保留，重建后可完成原导出 |
| `RecorderExportFlowTest` | 取消选择文件 | 只删除待导出快照，不改记录，不报告导出成功 |
| `RecorderExportFlowTest` | 打开目标失败，切批后重试 | 提示导出失败，保留旧快照；不重新准备即可完整重试，当前批次记录不受影响 |
| `RecorderExportFlowTest` | 写出部分内容后失败 | 目标已接受非空前缀后中断，报告失败且保留完整快照；重试从开头输出全部内容 |

导出预期字节直接写在用例中，独立核验 UTF-8、中文、emoji、空格、前导零、反斜杠和换行转义、CRLF 分行，以及不含序号、标题或批次信息。

## 隔离与故障注入

- 每个用例使用 UUID 数据库名。导出用例另用独立 `Application.filesDir`，不覆盖真实的 `pending-export.txt`。
- 写入失败通过仅针对测试数据库的 SQLite trigger 注入；不修改业务实现。
- `ExportFailureProvider` 只注册在测试 APK。它用限小发送缓冲的本地 socket pair 接受一个前缀后断开，再通过 Binder 返回实际接受的字节数，区分“打开失败”和“已经开始写出后失败”。
- Provider 独立进程只加载测试 APK；目标 APK 提供的 Kotlin 运行库不在该进程的类路径中，因此该夹具使用只依赖 Android 平台的 Java 实现。
- 模型操作、状态与一次性事件读取在主线程执行。清理前等待模型 worker 退出，再删除本用例的数据库与文件；测试通过反射等待 worker，业务代码不增加测试接口。

测试 APK 的 Provider 为跨 UID 的目标应用提供故障目标，因此仅测试清单设置 `exported=true`；正式 App 清单不注册该组件。

## 运行

先用项目脚本构建 App、测试 APK 并检查测试代码：

```powershell
.\scripts\build.ps1 -JdkHome '<本机 JDK 路径>' -Tasks @(
  ':recorder:assembleBundledDebug',
  ':recorder:assembleBundledDebugAndroidTest',
  ':recorder:detektBundledDebugAndroidTest'
)
```

启动数据位于 `build/avd` 的专用测试模拟器。确认模拟器序列号后显式指定目标，例如：

```powershell
adb -s emulator-5554 install -r recorder/build/outputs/apk/bundled/debug/recorder-bundled-debug.apk
adb -s emulator-5554 install -r recorder/build/outputs/apk/androidTest/bundled/debug/recorder-bundled-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r `
  -e class 'io.github.nuccd.scanrecorder.RecorderReviewFlowTest,io.github.nuccd.scanrecorder.RecorderExportFlowTest,io.github.nuccd.scanrecorder.RecorderStoreTest,io.github.nuccd.scanrecorder.BatchManagementStoreTest' `
  io.github.nuccd.scanrecorder.test/androidx.test.runner.AndroidJUnitRunner
```

这里的 adb 仅作为模拟器安装和运行测试的通道，不需要连接手机。判断运行结果应检查 `OK (25 tests)` 和每项状态；instrument 命令的进程退出码不能单独证明所有测试通过。

## 本轮报告（2026-11-18）

- Android API 37、x86_64 专用模拟器实际运行上述 4 个测试类，25 项全部通过，其中本轮新增 7 项。
- 测试 APK 构建、Kotlin/Java 编译以及 `:recorder:detektBundledDebugAndroidTest` 通过。
- 本轮未发现需要评估修复的新业务缺陷，业务源码未修改。
- 本轮日志保存在已忽略的 `build/token-audit/recorder-data-flow-instrumentation.log` 与 `build/token-audit/android-test-build.log`，不提交数据库、APK 或模拟器数据。

恢复测试重建 ViewModel 和 SavedState，文件目标使用自有文件或测试 Provider；这些证据不替代真实系统杀进程、系统文件选择器授权、真实第三方文档提供方或实机相机测试。
