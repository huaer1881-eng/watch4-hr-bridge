# Watch4 HR Bridge v0.5-ride

原生 Java Android 心率广播 App，目标 OPPO Watch 4 Pro OWW221 / Android 11 API30。
无 Wear OS、Google Play Services、.NET、网络、GPS或重量级UI依赖。

v0.4已由用户实测：真实85 BPM在手表与nRF手机端一致，CCCD通知开启，正状态样本通过，时钟偏差兼容有效。
v0.5将传感器/BLE移至前台服务，提供后台骑行、锁屏可靠模式和功耗对照记录；这些新行为仍需目标手表实测。

## 安装与开始

仓库根目录唯一工作流 `.github/workflows/main.yml`，成功后下载 **Watch4-HR-Test** artifact APK。
不需要本地 Android Studio。点击“开始骑行广播”后，可离开页面或锁屏；在页面或常驻通知中停止。
首次建议使用默认“锁屏可靠模式”。默认不常亮、不展示诊断日志。点击“显示诊断”查看raw和事件计数。
API12+未开放，本版运行范围Android8–11。

## 核心行为

- 原生前台服务与单工作线程；Activity只授权、控制与显示。
- 标准TYPE_HEART_RATE与BLE 180D / 2A37 / 2902，约1Hz输出最新真实样本。
- 保留accuracy过滤、OWW221最多2500ms未来时间兼容以及30秒无新有效事件暂停。
- BALANCED广告模式；心率订阅建立后暂停发现广告，所有订阅断开后恢复。
- 蓝牙关闭时暂停采集，重新开启恢复；GATT/广告/通知异常按2–60秒退避重试。
- 屏灭且采集活动时，可靠模式持有有限租约的PARTIAL_WAKE_LOCK；亮屏、暂停和停止释放。
- 省电试验模式取消CPU锁，屏灭是否持续更新必须实测。
- 页面可见时刷新，离开不刷新；系统通知按状态变化节流更新。
- 起止电量、时长、充电标记提供粗略功耗对照；没有虚构耗电结论。
- 显式停止释放资源；系统重建sticky服务时重新初始化。强行停止与重启不保证自动恢复，无开机自启动。

完整操作、逻辑、锁屏/功耗验收与日志见仓库根目录 [RIDING.md](../RIDING.md)。
旧版前台诊断步骤保留在 [TESTING.md](../TESTING.md)。

## 云端验证

AGP8.7.3 / Gradle8.9 / JDK17 / compileSdk35 / targetSdk35 / minSdk26。
CI执行APK编译、10个纯Java测试、lint、签名验证，并在Android11模拟器检查2个服务生命周期场景。
AndroidX测试依赖只进入测试APK。模拟器验证不能替代OPPO的锁屏心率、码表兼容和实际功耗测量。

