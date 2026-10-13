# Watch4 HR Test（OPPO Watch 4 Pro 心率 / BLE 测试）

**版本 0.1（实验测试版）**。这是一个零第三方运行时依赖的原生 Java Android App，不依赖 .NET、Wear OS 或 Google Play Services。

## 能做什么

- 请求 `android.permission.BODY_SENSORS`，监听 Android `Sensor.TYPE_HEART_RATE`，显示当前 BPM。
- 检查 `BluetoothAdapter.isMultipleAdvertisementSupported()` 和 `getBluetoothLeAdvertiser()`。
- 创建标准 BLE GATT Heart Rate Service `0x180D` / Heart Rate Measurement `0x2A37` / CCCD `0x2902`。
- 尝试 BLE connectable 广播，并显示 Android 广播错误码。
- 收到码表订阅时，通过 GATT Notify 发送 BPM；界面显示已连接和已订阅客户端数。

**注意**：未在真实 OPPO Watch 4 Pro / Magene C606 Pro 上实测。不能保证锁屏后台持续运行。这是功能诊断版，不是骑行正式版。

## 构建 APK：方案 A（Android Studio）

1. 安装 Android Studio（建议 2024.2+），在 SDK Manager 中安装 **Android SDK Platform 35** 和 Build-Tools。
2. `File → Open`，选中本工程的 `watch4_hr_bridge` 文件夹，等待 Gradle Sync。Android Studio 如询问 Gradle JDK，选内置 JBR 17。工程使用 Android Gradle Plugin 8.7.3（需 Gradle 8.9），Java 17。
3. 可直接双击工程根目录 `build-on-windows.cmd`（自动下载 Gradle 8.9 并构建；需要联网），也可以在 Android Studio 通过 `Build → Build App Bundle(s) / APK(s) → Build APK(s)` 构建。
4. 构建后得到根目录 `Watch4HR-test.apk`（双击脚本方式），或 `app/build/outputs/apk/debug/app-debug.apk`（Android Studio 方式）。
5. 将 APK 复制到你的 Windows `platform-tools` 文件夹，通过 USB 安装：

```bat
.\adb.exe devices
.\adb.exe install -r "Watch4HR-test.apk"  REM 若用 Android Studio 则换成 app-debug.apk
```

## 构建 APK：方案 B（GitHub Actions，无须本地 Android Studio）

本工程附有 `.github/workflows/build-apk.yml`。新建 GitHub 仓库，将工程文件（含 `.github` 文件夹）上传至仓库根目录；推送至 `main` 分支，或在 Actions 里手动运行 `Build test APK`。构建成功后，在该次 Actions 运行页面底部下载 `watch4-hr-test-apk` artifact，解压后得到 `app-debug.apk`。

## 使用步骤

1. 手表开启蓝牙，USB 调试状态保持亦可。先将此 APK 装到手表。
2. 佩戴手表，打开 **Watch4 HR Test**，点击 **开始检测与广播**。首次允许身体传感器权限。
3. 分别观察：
   - `心率：OK` 且 BPM 会变化：第三方 App 正在收到心率；
   - `BLE：OK`：BLE 广播回调已确认成功；
   - `码表：已连接 1，订阅心率 1`：存在 BLE 客户端连接且订阅了测量通知。
4. 打开 Magene C606 Pro → 传感器 → 添加心率传感器 → 搜索蓝牙心率。广播 UUID 为 `0x180D`，扫描设备名称通常仍为手表原名 `OPPO Watch ...`。
5. 将结果（BPM、BLE 状态、订阅数）拍照给我，或抓日志：

```bat
.\adb.exe logcat -c
REM 再到手表中点击「开始检测与广播」
.\adb.exe logcat -d -s Watch4HR:I '*:S' > watch4-test-log.txt
```

## BLE 错误码

- `1`：广告数据太大（程序会自动尝试不带设备名重试）
- `2`：广告资源不足
- `3`：已启动
- `4`：内部错误
- `5`：设备不支持该广播方式

如果只有心率 OK 但 BLE FAIL，不代表硬件一定不支持，可能是系统厂商权限/资源限制。可进一步分析 Logcat。

## 应用信息

- Package ID：`com.watch4.hrbridge`
- Android minSdk 26 / targetSdk 30（针对 Watch4 Pro Android 11）
- 无网络权限；不上传心率数据。
- 完全原生 Java + Android Framework API。
- 不含 Google、Wear OS、Xamarin、.NET 库。

## 卸载

```bat
.\adb.exe uninstall com.watch4.hrbridge
```
