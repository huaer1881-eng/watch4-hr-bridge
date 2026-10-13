# Watch4 HR Bridge v0.4：实机验收

目标：OWW221 / Android 11 API 30；接收端 Magene C606 Pro。当前版本只有前台验证。
构建参数：AGP 8.7.3 / Gradle 8.9 / JDK 17 / compileSdk 35 / targetSdk 35 / minSdk 26。
targetSdk 从 30 更新至 35，以通过完整 lint 的目标 API 检查；minSdk 26 保持对 Android 11 的安装支持。
运行时只开放 Android 8–11 测试，Android 12+ 的蓝牙运行时权限流程尚未提供。
云端编译、单元测试、lint、签名检查不证明 OPPO 传感器或 BLE 外设能力成功。

## 无心率但 accuracy 为 1/3 时

系统自带应用能测量，不能保证第三方 TYPE_HEART_RATE 获得相同读数。v0.4 显示原始事件及具体拒绝原因。
实机截图已显示正 raw=77，事件时间比App时间晚1800ms。v0.4仅对OWW221启用2500ms上限的未来时钟兼容，仍拒绝accuracy<=0。
先确认日志 DEVICE 或 MODEL 为 OWW221、屏幕窗口=2500ms，再检查accuracy=1/3时通过次数是否增加并显示实际BPM。
窗口内未来读数以接收时刻计算新鲜度，不延长30秒期限；原始事件时间保留在RAW_HR日志。超过窗口仍拒绝。

| 屏幕原因 | 下一步 |
| --- | --- |
| INVALID_BPM | 查看 raw 是否持续为 0 或非正常范围；收集 RAW_HR 的完整 values |
| UNRELIABLE / NO_CONTACT | 查看状态恢复到 1/3 后是否出现正 BPM；不将这类事件广播为真实心率 |
| FUTURE_TIME / STALE_TIME / OLD_SESSION | 收集 eventNs、elapsedMs、uptimeMs、sessionMs；超过未来兼容窗口或过期仍拒绝，不作无限制时间修正 |
| NO_EVENTS | 30 秒未收到传感器事件，检查监听注册、权限及厂商接口 |
| OK 且通过计数增加 | 已接收到符合校验的实际读数；观察后续 accuracy=0 是否立即清空 |

从系统心率应用退出后，进入本 App 点击开始，保持佩戴和前台 60 秒。
拍下 raw、accuracy、事件年龄、事件/正状态/通过计数和心率状态。需要日志时运行：

```bat
adb logcat -v threadtime Watch4HR:I *:S > watch4-clock-v04.txt
```

出现“签名不一致”请参考下文卸载后安装步骤。v0.4 的 versionCode=4，页面标题 WATCH4 HR TEST 0.4。
截图订阅数=0表示尚未开启2A37通知；连接成功后在nRF Connect选择Heart Rate Measurement的Enable notifications，再确认订阅=1。

## 获取与安装（Windows 10，不需要 Android Studio）

1. 打开本次成功的 Actions run，检查 commit SHA 与交付记录一致。
2. 下载 `Watch4-HR-Test` artifact ZIP，解压得到 `app-debug.apk` 和 `.sha256`。
3. 使用已有的 ADB / Android Platform Tools，在 APK 所在文件夹打开终端：

```bat
adb devices -l
adb shell getprop ro.product.device
adb shell getprop ro.build.version.sdk
adb install -r app-debug.apk
adb shell am start -n com.watch4.hrbridge/.MainActivity
```

PowerShell 可用 `Get-FileHash .\app-debug.apk -Algorithm SHA256` 与附带 SHA256 文件核对。
若出现 INSTALL_FAILED_UPDATE_INCOMPATIBLE，说明旧 APK 签名不同；确认本测试 App 无需保留的数据后：

```bat
adb uninstall com.watch4.hrbridge
adb install app-debug.apk
```

卸载会清除该 App 的权限和本地数据。Debug 签名每个云端 runner 可能不同。
本 App 无原生 .so、无 Mono，Java 字节码不限定 ARM64，可用于目标 32 位 ARM。

## 顺序验收与记录

| 阶段 | 操作 | 通过标准 |
| --- | --- | --- |
| 启动 | 打开 App，滚动页面，保持前台 | 不闪退、不持续卡顿 |
| 权限 | 点击开始，允许身体传感器权限；必要时再次点击开始 | registerListener=true；拒绝权限时可再次尝试 |
| 真实心率 | 正确佩戴，等待有效读数；改变活动强度 | 显示 BPM，日志出现 HR bpm 和 accuracy>0；数值随传感器事件变化 |
| 无接触/超时 | 摘下手表，观察厂商是否报告 accuracy=-1/0 | 无效后显示 -- BPM，停止发送；无新有效事件 30 秒后也停止发送 |
| BLE 启动 | 查看诊断和 ADB 日志 | advertiser 非 null，openGattServer 非 null，onServiceAdded status=0，onStartSuccess |
| 码表 | 在 C606 Pro 的传感器添加页面选择蓝牙心率并搜索 | 发现手表名称或无名称的心率设备；连接计数>0、订阅计数>0 |
| 通知 | 对比手表和码表显示 | 码表获得真实 BPM，更新有合理延迟；至少记录 5 分钟 |
| 重连 | 关闭再开启码表连接或离开后返回范围 | 断开后订阅清除；重连重新写 CCCD 后恢复读数 |
| 资源 | 连续开始/停止 10 次，再退出 App | 停止后连接/订阅归零；旧回调不重新启动广播；再次开始可用 |

搜索使用系统蓝牙名称，App 不修改全局蓝牙名称。若名称过长导致错误码 1，自动重试无名称广播，按心率设备类型辨认。
码表自身可能保持上一读数直到它的超时机制生效；App 不会伪造 0 BPM 来清除码表。

本传感器是 on-change：同一 BPM 不一定持续上报。本版只重发最近 30 秒内的真实样本；
超过 30 秒未有新事件会保守判为“无有效新事件”，这不等于已经检测到未佩戴。
若静止时频繁超时，提交带时间戳日志，下一步先核实厂商 on-change/accuracy 语义再改策略。
新样本可以重新恢复通知；通知完成回调缺失超过 5 秒则暂停该会话的发送，停止/开始后重试。
通知队列只保留每个订阅客户端的最新待发送状态，不积累旧 BPM。

## 日志收集

测试前执行，日志窗口保持运行，测试后 Ctrl+C：

```bat
adb logcat -c
adb logcat -v threadtime Watch4HR:I AndroidRuntime:E BluetoothGattServer:D BluetoothLeAdvertiser:D *:S > watch4-test-log.txt
```

另一个终端在出现问题时执行：

```bat
adb shell dumpsys sensorservice > sensorservice.txt
adb shell dumpsys bluetooth_manager > bluetooth-manager.txt
adb shell dumpsys package com.watch4.hrbridge > package.txt
adb logcat -b crash -d > crash.txt
```

若过滤日志不足，再收集 `adb logcat -d -v threadtime > logcat-full.txt`。
记录 APK commit、时间、佩戴状态、App BPM、码表 BPM、连接数/订阅数和错误码。
系统完整日志可能包含设备地址，分享前可以遮盖。

## BLE 广播错误与下一步

| 错误码 | 含义 | 排查 |
| --- | --- | --- |
| 1 | DATA_TOO_LARGE | 本版自动去名称重试；仍失败则检查实际广告/扫描响应数据与厂商实现 |
| 2 | TOO_MANY_ADVERTISERS | 关闭其他广播 App，停止后重试；必要时重启蓝牙 |
| 3 | ALREADY_STARTED | 查看开始/停止顺序和旧回调；停止后重启 App，保留日志 |
| 4 | INTERNAL_ERROR | 收集 bluetooth_manager 和完整 logcat；重启蓝牙/设备后复测 |
| 5 | FEATURE_UNSUPPORTED | 固件不支持该广播方式；不能用 BLE feature 或 GATT 存在来证明外设可用 |
| advertiser=null | 广播器不可用或蓝牙未开启 | 核对蓝牙 ON、multipleAdvertisementSupported 和固件能力 |
| openGattServer=null / addService 失败 | GATT 初始化失败 | 收集具体 status、系统蓝牙日志，关闭其他测试 App 后重试 |
| onServiceAdded/广播回调超时 | 厂商未完成异步操作 | 本版关闭 GATT；停止/开始或重启蓝牙，保留日志 |

如果广播成功但码表搜不到，可在另一台手机使用标准 BLE 客户端查看广播的 180D、
GATT 的 2A37/2902，并启用通知；这是排查步骤，不是已经完成的验证。
如果连接但订阅=0，查看 CCCD 写入结果；只接受 01 00（通知）和 00 00（取消），不接受 indication/prepared write。
若订阅>0 而无 BPM，先确认传感器读数有效且未过期，再检查通知返回值和 onNotificationSent status。

## 后台与功耗的后续阶段

当前 App 在 onStop 主动停止，锁屏/切换页面之后需回到 App 再点击开始。
测试期间请求保持亮屏，这会增加功耗，不代表最终骑行版功耗。
必须先通过前台端到端验收，再实现前台服务、锁屏和断线恢复，随后测 30–60 分钟稳定性与耗电。
不要把当前前台诊断版当作已完成后台保活的骑行版。

## 官方 API 依据

- [Heart rate accuracy and permission](https://developer.android.com/reference/android/hardware/Sensor#TYPE_HEART_RATE)
- [GATT notification completion](https://developer.android.com/reference/android/bluetooth/BluetoothGattServerCallback#onNotificationSent(android.bluetooth.BluetoothDevice,%20int))
- [BLE advertisement errors](https://developer.android.com/reference/android/bluetooth/le/AdvertiseCallback)
