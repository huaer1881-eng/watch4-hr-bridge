package com.watch4.hrbridge;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watch 4 Pro diagnostic. No Wear OS, Google Play Services, Compose or .NET.
 * Verifies Android heart rate sensor + LE advertiser and exposes standard
 * GATT Heart Rate Measurement notifications for an external bike computer.
 * This is a foreground-only proof-of-concept, not a ride-ready background app.
 */
public final class MainActivity extends Activity implements SensorEventListener {
    private static final UUID HR_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int REQUEST_BODY_SENSORS = 100;

    private final Set<String> subscribers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, BluetoothDevice> connected = new ConcurrentHashMap<>();
    private TextView bpmText, hrState, bleState, connectedState, logsText;
    private Button startButton, stopButton;
    private SensorManager sensorManager;
    private Sensor hrSensor;
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic hrCharacteristic;
    private volatile byte[] hrPayload = new byte[]{0, 0};
    private boolean sensorListening = false;
    private boolean advertising = false;
    private boolean starting = false;
    private boolean startedWithName = false;
    private final StringBuilder logs = new StringBuilder();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        hrSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        bluetoothAdapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        log("Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT);
        log("CPU: " + TextUtils.join(", ", Build.SUPPORTED_ABIS));
        if (hrSensor == null) {
            setHrState("HEART_RATE 未提供"); log("ERROR: 标准心率传感器不存在");
        } else {
            setHrState("传感器已发现：" + hrSensor.getName());
        }
        if (bluetoothAdapter == null) {
            setBleState("无 BluetoothAdapter");
        } else {
            log("蓝牙开关: " + bluetoothAdapter.isEnabled());
            log("多广告支持: " + bluetoothAdapter.isMultipleAdvertisementSupported());
            setBleState("待测试 BLE 广播");
        }
    }

    private int dp(float d) { return (int) (getResources().getDisplayMetrics().density * d + 0.5f); }
    private TextView text(String str, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(str); t.setTextColor(color); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(dp(3), dp(5), dp(3), dp(5));
        return t;
    }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(12, 17, 24));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(7), dp(16), dp(18));
        scroll.addView(root);
        TextView title = text("WATCH4 HR TEST", 16, Color.WHITE, true);
        title.setGravity(Gravity.CENTER); root.addView(title);
        bpmText = text("-- BPM", 33, Color.rgb(116, 224, 174), true);
        bpmText.setGravity(Gravity.CENTER); root.addView(bpmText);
        hrState = text("心率：待检查", 13, Color.WHITE, false); root.addView(hrState);
        bleState = text("BLE：待检查", 13, Color.WHITE, false); root.addView(bleState);
        connectedState = text("码表：尚未连接", 13, Color.WHITE, false); root.addView(connectedState);
        startButton = new Button(this);
        startButton.setText("开始检测与广播"); root.addView(startButton);
        stopButton = new Button(this);
        stopButton.setText("停止"); stopButton.setEnabled(false); root.addView(stopButton);
        TextView info = text("保持应用在前台；码表选择蓝牙心率，搜索 OPPO Watch。\n本版只用于验证，不保证锁屏后台运行。", 11, Color.LTGRAY, false);
        root.addView(info);
        logsText = text("日志", 11, Color.rgb(174, 196, 214), false);
        root.addView(logsText);
        startButton.setOnClickListener(v -> startOrAskPermission());
        stopButton.setOnClickListener(v -> stopAll());
        setContentView(scroll);
    }

    private void log(String message) {
        android.util.Log.i("Watch4HR", message);
        runOnUiThread(() -> {
            // Keep a bounded tail of the diagnostic log for a small watch screen.
            String[] parts = logs.toString().split("\\n");
            if (parts.length > 17) {
                logs.setLength(0);
                for (int i = parts.length - 12; i < parts.length; i++) logs.append(parts[i]).append('\n');
            }
            logs.append(message).append('\n');
            logsText.setText("诊断日志：\n" + logs);
        });
    }
    private void setHrState(String text) { runOnUiThread(() -> hrState.setText("心率：" + text)); }
    private void setBleState(String text) { runOnUiThread(() -> bleState.setText("BLE：" + text)); }
    private void setConnectedState() { runOnUiThread(() -> connectedState.setText("码表：已连接 " + connected.size() + "，订阅心率 " + subscribers.size())); }

    private void startOrAskPermission() {
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            log("请求 BODY_SENSORS 权限");
            requestPermissions(new String[]{Manifest.permission.BODY_SENSORS}, REQUEST_BODY_SENSORS);
            return;
        }
        beginTest();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == REQUEST_BODY_SENSORS) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) beginTest();
            else { log("FAIL: 身体传感器权限被拒绝"); setHrState("权限被拒绝"); }
        }
    }
    private void beginTest() {
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        if (hrSensor == null) {
            setHrState("FAIL：没有 HEART_RATE");
        } else {
            try {
                sensorListening = sensorManager.registerListener(this, hrSensor, 1_000_000);
                setHrState(sensorListening ? "监听中，等待心率事件……" : "FAIL：注册监听失败");
                log(sensorListening ? "OK: HEART_RATE 注册成功" : "FAIL: registerListener 返回 false");
            } catch (Exception e) { setHrState("FAIL: " + e.getClass().getSimpleName()); log("Sensor exception: " + e); }
        }
        startBluetooth();
    }
    private void startBluetooth() {
        if (bluetoothAdapter == null) { setBleState("FAIL：无蓝牙适配器"); return; }
        if (!bluetoothAdapter.isEnabled()) { setBleState("FAIL：先在手表设置中开启蓝牙"); return; }
        log("isMultipleAdvertisementSupported=" + bluetoothAdapter.isMultipleAdvertisementSupported());
        try { advertiser = bluetoothAdapter.getBluetoothLeAdvertiser(); }
        catch (Exception e) { setBleState("FAIL：获取广播器异常"); log("getAdvertiser: " + e); return; }
        if (advertiser == null) { setBleState("FAIL：getBluetoothLeAdvertiser() = null"); return; }
        log("OK: BLE advertiser 不为 null");
        try {
            gattServer = bluetoothManager.openGattServer(this, gattCallback);
            if (gattServer == null) { setBleState("FAIL：openGattServer 返回 null"); return; }
            hrCharacteristic = new BluetoothGattCharacteristic(
                    HR_MEASUREMENT_UUID,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    0);
            hrCharacteristic.addDescriptor(new BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
            BluetoothGattService service = new BluetoothGattService(
                    HR_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            service.addCharacteristic(hrCharacteristic);
            if (!gattServer.addService(service)) {
                setBleState("FAIL：addService 返回 false"); log("FAIL: addService(false)");
            } else {
                starting = true;
                setBleState("GATT 服务添加中……");
            }
        } catch (Exception e) { setBleState("FAIL：GATT 异常"); log("GATT exception: " + e); }
    }
    private void startAdvertising(boolean includeName) {
        if (advertiser == null) return;
        startedWithName = includeName;
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        AdvertiseData adv = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(new ParcelUuid(HR_SERVICE_UUID))
                .build();
        AdvertiseData scan = includeName
                ? new AdvertiseData.Builder().setIncludeDeviceName(true).build()
                : new AdvertiseData.Builder().build();
        try {
            setBleState("启动广播中……");
            advertiser.startAdvertising(settings, adv, scan, advertiseCallback);
        } catch (Exception e) { setBleState("FAIL：startAdvertising 异常"); log("Advertising exception: " + e); }
    }
    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settings) {
            advertising = true;
            starting = false;
            setBleState("OK：BLE 心率广播已启动");
            log("SUCCESS: BLE advertise + 0x180D 已启动，开始在 C606 Pro 搜索");
        }
        @Override public void onStartFailure(int errorCode) {
            advertising = false;
            starting = false;
            log("FAIL: BLE 广播错误码=" + errorCode);
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE && startedWithName) {
                log("广播名称太长，重试无名称模式"); startAdvertising(false); return;
            }
            setBleState("FAIL：广播错误码 " + errorCode + "（1数据过大 2资源不足 3已启动 4内部错误 5不支持）");
        }
    };
    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int status, BluetoothGattService service) {
            log("GATT onServiceAdded=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS && starting) runOnUiThread(() -> startAdvertising(true));
            else setBleState("FAIL：GATT addService 状态 " + status);
        }
        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            String id = device.getAddress();
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                connected.put(id, device); log("GATT 客户端已连接：" + id);
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                connected.remove(id); subscribers.remove(id); log("GATT 客户端已断开：" + id);
            }
            setConnectedState();
        }
        @Override public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
            if (gattServer == null) return;
            if (!CCCD_UUID.equals(descriptor.getUuid())) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null); return;
            }
            byte[] value = subscribers.contains(device.getAddress())
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            if (offset > value.length) gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
            else gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, Arrays.copyOfRange(value, offset, value.length));
        }
        @Override public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor,
                                                        boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            int code = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
            if (CCCD_UUID.equals(descriptor.getUuid()) && !preparedWrite && offset == 0 && value != null && value.length == 2) {
                if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    subscribers.add(device.getAddress()); code = BluetoothGatt.GATT_SUCCESS;
                    log("OK: " + device.getAddress() + " 订阅心率通知");
                } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    subscribers.remove(device.getAddress()); code = BluetoothGatt.GATT_SUCCESS;
                    log("OK: 客户端取消订阅");
                }
            }
            if (responseNeeded && gattServer != null) gattServer.sendResponse(device, requestId, code, offset, null);
            setConnectedState();
        }
        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                           BluetoothGattCharacteristic characteristic) {
            if (gattServer != null) gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null);
        }
    };

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_HEART_RATE || event.values.length == 0) return;
        float raw = event.values[0];
        if (Float.isNaN(raw) || raw <= 0 || raw >= 1024) {
            setHrState("读到无效心率值：" + raw);
            return;
        }
        int bpm = Math.round(raw);
        runOnUiThread(() -> bpmText.setText(bpm + " BPM"));
        setHrState("OK：收到实时心率数据");
        // Bluetooth SIG Heart Rate Measurement characteristic (0x2A37).
        // Byte 0 flags = 0x00 for uint8, or 0x01 for uint16 BPM.
        if (bpm <= 255) hrPayload = new byte[]{0x00, (byte) bpm};
        else hrPayload = new byte[]{0x01, (byte) (bpm & 0xff), (byte) ((bpm >> 8) & 0xff)};
        if (gattServer != null && hrCharacteristic != null && !subscribers.isEmpty()) {
            hrCharacteristic.setValue(hrPayload);
            for (String id : subscribers) {
                BluetoothDevice device = connected.get(id);
                if (device != null) {
                    try {
                        if (!gattServer.notifyCharacteristicChanged(device, hrCharacteristic, false))
                            log("WARN: 通知发送失败，客户端=" + id);
                    } catch (Exception e) { log("Notify exception: " + e.getClass().getSimpleName()); }
                }
            }
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    private void stopAll() {
        starting = false;
        if (sensorManager != null && sensorListening) sensorManager.unregisterListener(this);
        sensorListening = false;
        if (advertiser != null && advertising) {
            try { advertiser.stopAdvertising(advertiseCallback); } catch (Exception ignored) { }
        }
        advertising = false;
        if (gattServer != null) { try { gattServer.close(); } catch (Exception ignored) { } gattServer = null; }
        connected.clear(); subscribers.clear();
        setBleState("已停止"); setHrState("已停止"); setConnectedState();
        startButton.setEnabled(true); stopButton.setEnabled(false);
        log("用户停止检测");
    }
    @Override protected void onDestroy() { stopAll(); super.onDestroy(); }
}
