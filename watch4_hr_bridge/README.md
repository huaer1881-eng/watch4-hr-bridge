# Watch4 HR Bridge v0.3-diagnostic

原生 Java Android 前台诊断 App，将 OPPO Watch 4 Pro 的真实心率作为标准 BLE 心率服务发送给 Magene C606 Pro。无 Wear OS、Google Play Services、.NET、网络权限或第三方运行时依赖。

## 云端获取 APK

仓库根目录唯一工作流为 `.github/workflows/main.yml`。推送 main 或手动运行 **Build Watch4 APK**，成功后下载 **Watch4-HR-Test** artifact，解压得到 `app-debug.apk` 和 SHA256 文件。不需要本地 Android Studio。

构建固定为 AGP 8.7.3、Gradle 8.9、JDK 17、SDK/Build Tools 35；完整执行 assembleDebug、5 项心率协议单元测试、lint 和 APK 签名检查。缺少 APK 会失败，不上传失败构建的安装包。

## 当前范围

- minSdk 26 / targetSdk 35；目标实机为 OWW221 / Android 11 API 30。Android 12+ 暂不开放测试。
- BODY_SENSORS 权限与 TYPE_HEART_RATE；丢弃无接触、不可靠、无效或过期读数。
- GATT 180D / Notify 2A37 / CCCD 2902，等待服务添加成功后才广播。
- 每个客户端独立订阅；通知逐条等完成回调，队列只保留最新样本。
- 每秒可重发最近真实样本；30 秒无新有效事件清空 BPM 并暂停通知。
- 广播错误码 1 自动去名称重试；停止/退出前台释放传感器、广播和 GATT。
- 小屏原生 UI 与有界诊断日志；测试时保持亮屏。

编译成功不等于已通过实机验证。尚需确认第三方 App 在 OPPO 固件下持续收到有效 BPM、外设广播成功、C606 Pro 订阅并显示实时读数。后台、锁屏保活与骑行功耗留待前台验收后开发。

完整 Windows ADB 命令、顺序验收、日志收集和错误码排查见仓库根目录 [TESTING.md](../TESTING.md)。

## v0.3 心率诊断

用于定位“accuracy 在 0/1/3 变化但无 BPM”的实机反馈。屏幕新增 raw、accuracy、事件年龄和事件/正状态/通过计数；日志 RAW_HR 包含完整标准事件值、事件时间、elapsedRealtime、uptime 与每次校验原因。
准确性状态 1/3 原本就允许通过；本版没有放宽无效读数规则，也不替换事件时间戳。
诊断原因包括 INVALID_BPM、UNRELIABLE、NO_CONTACT、FUTURE_TIME、STALE_TIME、OLD_SESSION。
请先在系统应用完成一次测量，退出后进入本 App 保持前台 60 秒，发送原始事件区域与状态截图。

