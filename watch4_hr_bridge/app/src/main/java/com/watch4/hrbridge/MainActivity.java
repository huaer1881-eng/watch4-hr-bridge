package com.watch4.hrbridge;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.*;
import android.os.*;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.*;
import java.util.*;

/** Foreground diagnostic for Android 11. All mutable state is owned by the main handler. */
public final class MainActivity extends Activity implements SensorEventListener {
    private static final UUID HR_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int REQUEST_BODY_SENSORS = 100;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HeartRateSample sample = new HeartRateSample();
    private final Map<String, BluetoothDevice> connected = new LinkedHashMap<>();
    private final Set<String> subscribers = new HashSet<>();
    private final Set<String> pending = new LinkedHashSet<>();
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private TextView bpmText, hrState, bleState, connectedState, logsText;
    private Button startButton, stopButton;
    private SensorManager sensorManager;
    private Sensor hrSensor;
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic hrCharacteristic;
    private AdvertiseCallback advertiseCallback;
    private boolean active, foreground, sensorListening, receiverRegistered, requestingPermission;
    private boolean hadSample, notificationBlocked;
    private boolean advertising;
    private int generation;
    private String inFlight;
    private long notificationStarted, sessionStarted;
    private int lastLoggedBpm = -1;
    private long lastSampleLog;
    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (active && intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON)
                stopAll("蓝牙已关闭；重新开启后点击开始");
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        hrSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        bluetoothAdapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        log("Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT);
        log("CPU: " + TextUtils.join(", ", Build.SUPPORTED_ABIS));
        setHrState(hrSensor == null ? "未提供 HEART_RATE" : "已发现：" + hrSensor.getName());
        setBleState("待测试 BLE 广播");
        // Avoid accidental use of legacy permissions on a different OS.
        if (Build.VERSION.SDK_INT != 30) log("本版验收目标为 API 30，其他系统尚未验证");
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
        TextView info = text("保持应用在前台；码表选择蓝牙心率，搜索 OPPO Watch。\n前台测试版；离开页面会停止。", 11, Color.LTGRAY, false);
        root.addView(info);
        logsText = text("日志", 11, Color.rgb(174, 196, 214), false);
        root.addView(logsText);
        startButton.setOnClickListener(v -> startOrAskPermission());
        stopButton.setOnClickListener(v -> stopAll("用户停止"));
        setContentView(scroll);
    }


    private void log(String message) {
        android.util.Log.i("Watch4HR", message);
        if (logs.size() == 14) logs.removeFirst();
        logs.addLast(message);
        logsText.setText("诊断日志：\n" + TextUtils.join("\n", logs));
    }
    private void setHrState(String s) { hrState.setText("心率：" + s); }
    private void setBleState(String s) { bleState.setText("BLE：" + s); }
    private void setConnectedState() {
        connectedState.setText("码表：已连接 " + connected.size() + "，订阅 " + subscribers.size());
    }
    private void startOrAskPermission() {
        if (active || requestingPermission) return;
        if (Build.VERSION.SDK_INT > 30) {
            setBleState("本测试版只支持 Android 8–11；目标手表 API 30");
            return;
        }
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            requestingPermission = true;
            requestPermissions(new String[]{Manifest.permission.BODY_SENSORS}, REQUEST_BODY_SENSORS);
            return;
        }
        beginTest();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != REQUEST_BODY_SENSORS) return;
        requestingPermission = false;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            // Permission results can precede onResume; use the window focus callback.
            if (foreground && hasWindowFocus()) beginTest();
            else startButton.setText("权限已授予，点击开始");
        } else {
            log("FAIL: BODY_SENSORS 权限被拒绝");
            setHrState("权限被拒绝；可再次点击开始");
        }
    }
    private void beginTest() {
        if (active || !foreground) return;
        active = true;
        final int token = ++generation;
        sessionStarted = SystemClock.elapsedRealtime();
        sample.clear(); hadSample = false; notificationBlocked = false;
        startButton.setText("检测中"); startButton.setEnabled(false); stopButton.setEnabled(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver,
                    new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_EXPORTED);
            else registerReceiver(bluetoothReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
            receiverRegistered = true;
            if (hrSensor != null) {
                sensorListening = sensorManager.registerListener(this, hrSensor, 1_000_000, main);
                setHrState(sensorListening ? "等待真实 BPM…" : "FAIL：注册监听返回 false");
                log("registerListener=" + sensorListening);
            } else setHrState("FAIL：无标准心率传感器");
            startBluetooth(token);
        } catch (SecurityException e) { log("权限异常：" + e); } catch (RuntimeException e) { failBle("初始化异常 " + e, token); }
        main.postDelayed(tick, 1000);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            long now = SystemClock.elapsedRealtime();
            if (!sample.fresh(now)) {
                if (hadSample || now - sessionStarted >= HeartRateSample.MAX_AGE_MS) {
                    invalidateSample("30 秒内无有效新事件；已暂停通知");
                }
            } else {
                // Re-send only a recent REAL sample, useful with an on-change sensor.
                pending.addAll(subscribers);
                drainNotifications();
            }
            if (inFlight != null && now - notificationStarted > 5000 && !notificationBlocked) {
                notificationBlocked = true; pending.clear();
                log("FAIL: onNotificationSent 超时；停止/开始后重试");
                setBleState("通知回调超时，请停止后重新开始");
            }
            main.postDelayed(this, 1000);
        }
    };

    private void startBluetooth(int token) {
        try {
        if (bluetoothAdapter == null) { failBle("无 BluetoothAdapter", token); return; }
        if (!bluetoothAdapter.isEnabled()) { failBle("先开启手表蓝牙", token); return; }
        log("isMultipleAdvertisementSupported=" + bluetoothAdapter.isMultipleAdvertisementSupported());
        advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        log("getBluetoothLeAdvertiser=" + (advertiser == null ? "null" : "非 null"));
        if (advertiser == null) { failBle("广播器为空", token); return; }
        gattServer = bluetoothManager.openGattServer(this, callbacks(token));
        log("openGattServer=" + (gattServer == null ? "null" : "非 null"));
        if (gattServer == null) { failBle("GATT Server 为空", token); return; }
        hrCharacteristic = new BluetoothGattCharacteristic(HR_MEASUREMENT_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0);
        hrCharacteristic.addDescriptor(new BluetoothGattDescriptor(CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        BluetoothGattService service = new BluetoothGattService(HR_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(hrCharacteristic);
        // State is ready before addService; callback work is posted to this same handler.
        setBleState("GATT 服务添加中…");
        if (!gattServer.addService(service)) { failBle("addService 返回 false", token); return; }
        main.postDelayed(() -> {
            if (current(token) && advertiseCallback == null) failBle("onServiceAdded 超时", token);
        }, 10_000);
    }
        catch (SecurityException e) { failBle("蓝牙权限异常 " + e, token); }
    }
    private boolean current(int token) { return active && generation == token; }
    private void failBle(String reason, int token) {
        if (!current(token)) return;
        log("FAIL: " + reason);
        closeBle();
        // Invalidate callbacks while allowing sensor-only diagnostics to continue.
        generation++;
        setBleState("FAIL：" + reason + "；停止后重试");
    }
    private void startAdvertising(int token, boolean includeName) {
        if (!current(token) || advertiser == null) return;
        final BluetoothLeAdvertiser owner = advertiser;
        final AdvertiseCallback callback = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings settings) { main.post(() -> {
                if (!current(token) || advertiseCallback != this) {
                    try { owner.stopAdvertising(this); } catch (SecurityException e) { log("权限异常：" + e); } catch (RuntimeException e) { log("取消旧广播：" + e); }
                    return;
                }
                setBleState("OK：广播回调成功 0x180D");
                advertising = true;
                log("SUCCESS: onStartSuccess；尚需码表实测");
            }); }
            @Override public void onStartFailure(int errorCode) { main.post(() -> {
                if (!current(token) || advertiseCallback != this) return;
                log("FAIL: advertise errorCode=" + errorCode);
                if (includeName && errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE) {
                    try { owner.stopAdvertising(this); }
                    catch (SecurityException e) { failBle("取消广播权限异常 " + e, token); return; }
                    catch (RuntimeException e) { failBle("取消广播异常 " + e, token); return; }
                    advertiseCallback = null;
                    startAdvertising(token, false);
                } else failBle("广播错误码 " + errorCode, token);
            }); }
        };
        advertiseCallback = callback;
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true).setTimeout(0).build();
        // Flags + a 16-bit service UUID fit into the legacy 31-byte advertisement.
        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(HR_SERVICE_UUID)).build();
        AdvertiseData scan = new AdvertiseData.Builder().setIncludeDeviceName(includeName).build();
        log("广播名称模式=" + includeName);
        setBleState("启动广播中…");
        try { owner.startAdvertising(settings, data, scan, callback); }
        catch (SecurityException e) { log("权限异常：" + e); } catch (RuntimeException e) { failBle("startAdvertising 异常 " + e, token); }
        main.postDelayed(() -> {
            if (current(token) && advertiseCallback == callback && !advertising)
                failBle("广播启动回调超时", token);
        }, 10_000);
    }

    private BluetoothGattServerCallback callbacks(final int token) {
        return new BluetoothGattServerCallback() {
            private void dispatch(Runnable action) {
                main.post(() -> {
                    if (!current(token) || gattServer == null) return;
                    try { action.run(); }
                    catch (SecurityException e) { log("权限异常：" + e); } catch (RuntimeException e) { failBle("GATT 回调异常 " + e, token); }
                });
            }
            @Override public void onServiceAdded(int status, BluetoothGattService service) {
                dispatch(() -> { try {
                    log("onServiceAdded status=" + status);
                    if (status == BluetoothGatt.GATT_SUCCESS && HR_SERVICE_UUID.equals(service.getUuid()))
                        startAdvertising(token, true);
                    else failBle("addService status=" + status, token);
                } catch (SecurityException e) { failBle("权限被撤销 " + e, token); }
                });
            }
            @Override public void onConnectionStateChange(BluetoothDevice d, int status, int state) {
                dispatch(() -> { try {
                    String id = d.getAddress();
                    log("连接 status=" + status + " state=" + state);
                    if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                        connected.put(id, d);
                        subscribers.remove(id); pending.remove(id);
                    } else {
                        connected.remove(id); subscribers.remove(id); pending.remove(id);
                        // Keep an outstanding notification until its callback or timeout.
                        // Clearing it now could misattribute a delayed callback after reconnect.
                    }
                    setConnectedState();
                } catch (SecurityException e) { failBle("权限被撤销 " + e, token); }
                });
            }
            @Override public void onDescriptorReadRequest(BluetoothDevice d, int id, int offset,
                                                          BluetoothGattDescriptor descriptor) {
                dispatch(() -> { try {
                    if (!isCccd(descriptor)) { respond(d, id, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null); return; }
                    byte[] v = subscribers.contains(d.getAddress())
                            ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                    if (offset < 0 || offset > v.length) respond(d, id, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
                    else respond(d, id, BluetoothGatt.GATT_SUCCESS, offset, Arrays.copyOfRange(v, offset, v.length));
                } catch (SecurityException e) { failBle("权限被撤销 " + e, token); }
                });
            }
            @Override public void onDescriptorWriteRequest(BluetoothDevice d, int id,
                    BluetoothGattDescriptor descriptor, boolean prepared, boolean responseNeeded, int offset, byte[] value) {
                // The Bluetooth callback's byte array must not be retained without copying.
                final byte[] copy = value == null ? null : value.clone();
                dispatch(() -> { try {
                    int result = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                    boolean enabled = false;
                    if (isCccd(descriptor) && !prepared) {
                        if (offset != 0) result = BluetoothGatt.GATT_INVALID_OFFSET;
                        else if (copy == null || copy.length != 2) result = BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH;
                        else if (Arrays.equals(copy, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                && connected.containsKey(d.getAddress())) {
                            subscribers.add(d.getAddress()); enabled = true; result = BluetoothGatt.GATT_SUCCESS;
                        } else if (Arrays.equals(copy, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                            subscribers.remove(d.getAddress()); pending.remove(d.getAddress()); result = BluetoothGatt.GATT_SUCCESS;
                        }
                    }
                    if (responseNeeded) respond(d, id, result, offset, null);
                    log("CCCD status=" + result + " subscribed=" + subscribers.size());
                    setConnectedState();
                    if (enabled && sample.fresh(SystemClock.elapsedRealtime())) {
                        pending.add(d.getAddress()); drainNotifications();
                    }
                } catch (SecurityException e) { failBle("权限被撤销 " + e, token); }
                });
            }
            @Override public void onExecuteWrite(BluetoothDevice d, int id, boolean execute) {
                dispatch(() -> respond(d, id, execute ? BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                        : BluetoothGatt.GATT_SUCCESS, 0, null));
            }
            @Override public void onCharacteristicReadRequest(BluetoothDevice d, int id, int offset,
                    BluetoothGattCharacteristic c) {
                dispatch(() -> respond(d, id, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null));
            }
            @Override public void onCharacteristicWriteRequest(BluetoothDevice d, int id,
                    BluetoothGattCharacteristic c, boolean prepared, boolean responseNeeded, int offset, byte[] value) {
                if (responseNeeded) dispatch(() -> respond(d, id, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null));
            }
            @Override public void onNotificationSent(BluetoothDevice d, int status) {
                dispatch(() -> { try {
                    if (!d.getAddress().equals(inFlight)) return;
                    inFlight = null;
                    if (status != BluetoothGatt.GATT_SUCCESS) log("通知完成失败 status=" + status);
                    if (!notificationBlocked) drainNotifications();
                } catch (SecurityException e) { failBle("权限被撤销 " + e, token); }
                });
            }
        };
    }
    private boolean isCccd(BluetoothGattDescriptor d) {
        return CCCD_UUID.equals(d.getUuid()) && d.getCharacteristic() != null
                && HR_MEASUREMENT_UUID.equals(d.getCharacteristic().getUuid());
    }
    private void respond(BluetoothDevice d, int id, int status, int offset, byte[] value) {
        try {
            if (!gattServer.sendResponse(d, id, status, offset, value)) log("WARN: sendResponse=false");
        } catch (SecurityException e) { log("sendResponse 权限异常：" + e); }
    }
    private void drainNotifications() {
        if (gattServer == null || hrCharacteristic == null || inFlight != null || notificationBlocked) return;
        byte[] payload = sample.payload(SystemClock.elapsedRealtime());
        if (payload == null) { pending.clear(); return; }
        while (!pending.isEmpty()) {
            String id = pending.iterator().next(); pending.remove(id);
            BluetoothDevice d = connected.get(id);
            if (d == null || !subscribers.contains(id)) continue;
            hrCharacteristic.setValue(payload);
            inFlight = id; notificationStarted = SystemClock.elapsedRealtime();
            try {
                if (gattServer.notifyCharacteristicChanged(d, hrCharacteristic, false)) return;
                inFlight = null; log("WARN: notifyCharacteristicChanged=false");
            } catch (SecurityException e) {
                inFlight = null;
                failBle("通知权限异常 " + e, generation);
                return;
            } catch (RuntimeException e) { inFlight = null; log("通知异常：" + e); }
        }
    }
    @Override public void onSensorChanged(SensorEvent e) {
        if (!active || !sensorListening || e.sensor.getType() != Sensor.TYPE_HEART_RATE || e.values.length == 0) return;
        long now = SystemClock.elapsedRealtime();
        if (e.timestamp / 1_000_000 < sessionStarted) return;
        if (!sample.accept(e.values[0], e.accuracy, e.timestamp / 1_000_000, now)) {
            invalidateSample("无效或无接触，accuracy=" + e.accuracy);
            return;
        }
        hadSample = true;
        bpmText.setText(sample.bpm() + " BPM");
        setHrState("真实 BPM，accuracy=" + e.accuracy);
        if (sample.bpm() != lastLoggedBpm || now - lastSampleLog >= 10_000) {
            log("HR bpm=" + sample.bpm() + " accuracy=" + e.accuracy);
            lastLoggedBpm = sample.bpm(); lastSampleLog = now;
        }
        pending.addAll(subscribers); drainNotifications();
    }
    @Override public void onAccuracyChanged(Sensor s, int accuracy) {
        if (active && s.getType() == Sensor.TYPE_HEART_RATE && accuracy <= 0)
            invalidateSample("无可靠读数或未佩戴，accuracy=" + accuracy);
    }
    private void invalidateSample(String reason) {
        sample.clear(); pending.clear(); hadSample = false;
        bpmText.setText("-- BPM");
        if (!hrState.getText().toString().equals("心率：" + reason)) log(reason);
        setHrState(reason);
    }
    private void closeBle() {
        if (advertiser != null && advertiseCallback != null) {
            try { advertiser.stopAdvertising(advertiseCallback); } catch (SecurityException e) { log("权限异常：" + e); } catch (RuntimeException e) { log("stopAdvertising：" + e); }
        }
        advertiseCallback = null; advertiser = null; advertising = false;
        if (gattServer != null) {
            try { gattServer.close(); } catch (SecurityException e) { log("权限异常：" + e); } catch (RuntimeException e) { log("close GATT：" + e); }
        }
        gattServer = null; hrCharacteristic = null;
        connected.clear(); subscribers.clear(); pending.clear(); inFlight = null;
        setConnectedState();
    }
    private void stopAll(String reason) {
        if (!active) return;
        active = false; generation++;
        main.removeCallbacks(tick);
        if (sensorManager != null && sensorListening) {
            try { sensorManager.unregisterListener(this); } catch (SecurityException e) { log("权限异常：" + e); } catch (RuntimeException e) { log("unregister sensor：" + e); }
        }
        sensorListening = false;
        closeBle();
        if (receiverRegistered) { unregisterReceiver(bluetoothReceiver); receiverRegistered = false; }
        sample.clear(); hadSample = false; bpmText.setText("-- BPM");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startButton.setText("开始检测与广播");
        startButton.setEnabled(true); stopButton.setEnabled(false);
        setBleState("已停止"); setHrState("已停止");
        log(reason);
    }
    @Override protected void onResume() { super.onResume(); foreground = true; }
    @Override protected void onStop() { foreground = false; stopAll("离开前台，资源已释放"); super.onStop(); }
    @Override protected void onDestroy() { stopAll("应用关闭"); main.removeCallbacksAndMessages(null); super.onDestroy(); }
}


