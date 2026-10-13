package com.watch4.hrbridge;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.*;
import android.os.*;
import android.text.TextUtils;
import java.util.*;

/** Sensor and BLE state live on one worker looper, independent of any Activity. */
final class RideEngine implements SensorEventListener {
    interface Listener { void onState(State state); }
    static final class State {
        final boolean running, sensorListening;
        final int bpm, connections, subscriptions, events, accepted;
        final long startedMs, notificationCount;
        final String hr, ble, raw, logs;
        State(boolean running, boolean listening, int bpm, int connections, int subscriptions,
                int events, int accepted, long startedMs, long notifications,
                String hr, String ble, String raw, String logs) {
            this.running=running; this.sensorListening=listening; this.bpm=bpm;
            this.connections=connections; this.subscriptions=subscriptions;
            this.events=events; this.accepted=accepted; this.startedMs=startedMs;
            this.notificationCount=notifications; this.hr=hr; this.ble=ble; this.raw=raw; this.logs=logs;
        }
        static State idle() { return new State(false,false,0,0,0,0,0,0,0,"未开始","未开始","",""); }
    }
    private static final UUID HR_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final Handler main; // Worker handler; no UI work occurs here.
    private final Listener listener;
    private final long futureSkewMs = ("OWW221".equalsIgnoreCase(Build.DEVICE)
            || "OWW221".equalsIgnoreCase(Build.MODEL)) ? HeartRateSample.MAX_FUTURE_SKEW_MS : 0;
    private final HeartRateSample sample = new HeartRateSample(futureSkewMs);
    private final Map<String,BluetoothDevice> connected = new LinkedHashMap<>();
    private final Set<String> subscribers = new HashSet<>();
    private final Set<String> pending = new LinkedHashSet<>();
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private final RetryBackoff retry = new RetryBackoff();
    private SensorManager sensorManager;
    private Sensor hrSensor;
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic hrCharacteristic;
    private AdvertiseCallback advertiseCallback;
    private boolean active, sensorListening, advertising, serviceReady, hadSample, notificationBlocked, diagnostics;
    private int generation, lastLoggedBpm=-1, sensorEvents, acceptedEvents, positiveAccuracyEvents;
    private long sessionStarted, notificationStarted, notificationCount, lastQueued=-1000;
    private long lastSampleLog, lastRawDisplay=-1, lastRawLog=-1, sensorRegisteredAt;
    private String inFlight, hrState="未开始", bleState="未开始", rawText="";
    RideEngine(Context context, Handler handler, Listener listener) {
        this.context=context.getApplicationContext(); this.main=handler; this.listener=listener;
        sensorManager=(SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        bluetoothManager=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
        hrSensor=sensorManager==null?null:sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        bluetoothAdapter=bluetoothManager==null?null:bluetoothManager.getAdapter();
    }
    void start() { main.post(() -> {
        if(active)return;
        active=true; generation++; sessionStarted=SystemClock.elapsedRealtime();
        sensorEvents=0; acceptedEvents=0; positiveAccuracyEvents=0; notificationCount=0;
        lastRawDisplay=-1; lastRawLog=-1; lastQueued=-1000; sample.clear(); retry.reset();
        log("RIDE_START API="+Build.VERSION.SDK_INT+" DEVICE="+Build.DEVICE+" MODEL="+Build.MODEL
                +" futureToleranceMs="+futureSkewMs);
        ensureSensor();
        if(hrSensor!=null) log("SENSOR="+hrSensor.getName()+" wakeUp="+hrSensor.isWakeUpSensor());
        try { startBluetooth(generation); } catch(RuntimeException e){failBle("蓝牙初始化异常 "+e,generation);}
        main.post(tick);
    }); }
    void setDiagnostics(boolean enabled) { main.post(() -> {diagnostics=enabled; publish();}); }
    void bluetoothChanged(int state) { main.post(() -> {
        if(!active)return;
        if(state==BluetoothAdapter.STATE_ON) {
            ensureSensor();
            if(gattServer==null) { main.removeCallbacks(recover); retry.reset(); recover.run(); }
        } else if(state==BluetoothAdapter.STATE_TURNING_OFF || state==BluetoothAdapter.STATE_OFF) {
            generation++; main.removeCallbacks(recover); closeBle(); pauseSensor();
            setBleState("蓝牙已关闭，等待重新开启"); publish();
        }
    }); }
    private void ensureSensor() {
        if(sensorListening)return;
        if(hrSensor==null){setHrState("未提供标准心率传感器");return;}
        try {
            if(context.checkSelfPermission(Manifest.permission.BODY_SENSORS)!=PackageManager.PERMISSION_GRANTED) {
                setHrState("身体传感器权限已撤销，请停止后重新授权"); return;
            }
            sensorRegisteredAt=SystemClock.elapsedRealtime();
            sensorListening=sensorManager.registerListener(this,hrSensor,1_000_000,main);
            setHrState(sensorListening?"等待真实 BPM":"注册监听失败");
        } catch(SecurityException e){setHrState("传感器权限异常");log(e.toString());}
        catch(RuntimeException e){setHrState("传感器初始化异常");log(e.toString());}
    }
    private void pauseSensor() {
        if(sensorListening) {
            try {sensorManager.unregisterListener(this);} catch(RuntimeException e){log(e.toString());}
        }
        sensorListening=false; sample.clear(); hadSample=false; pending.clear();
        setHrState("采集已暂停");
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(!active)return;
        long now=SystemClock.elapsedRealtime();
        if(!sample.fresh(now)){
            if(hadSample)invalidateSample("30秒无有效新样本，等待传感器更新");
            else if(sensorEvents==0 && now-sessionStarted>=HeartRateSample.MAX_AGE_MS)
                setHrState("未收到心率事件，请查看权限或佩戴状态");
        } else queueLatest();
        if(inFlight!=null && now-notificationStarted>5000 && !notificationBlocked) {
            notificationBlocked=true; failBle("通知回调超时，自动恢复",generation);
        }
        publish();
        main.postDelayed(this,1000);
    }};
    private void queueLatest(){
        long now=SystemClock.elapsedRealtime();
        if(subscribers.isEmpty() || !sample.fresh(now) || now-lastQueued<1000)return;
        lastQueued=now; pending.addAll(subscribers); drainNotifications();
    }
    private final Runnable recover=()->{
        if(!active || gattServer!=null)return;
        try {
            if(bluetoothAdapter==null || !bluetoothAdapter.isEnabled())return;
            notificationBlocked=false; startBluetooth(generation);
        } catch(RuntimeException e){failBle("恢复异常 "+e,generation);}
    };
    private void scheduleRecovery(){
        main.removeCallbacks(recover);
        if(active){long delay=retry.nextDelayMs(); log("BLE_RETRY delayMs="+delay);main.postDelayed(recover,delay);}
    }
    private void stopAdvertisingOnly(){
        if(advertiser!=null && advertiseCallback!=null){
            try {advertiser.stopAdvertising(advertiseCallback);}
            catch(SecurityException e){log("停止广播权限异常");}
            catch(RuntimeException e){log("停止广播异常 "+e);}
        }
        advertiseCallback=null; advertising=false;
    }
    private void setHrState(String s){hrState=s;}
    private void setBleState(String s){bleState=s;publish();}
    private void setConnectedState(){publish();}
    private void log(String message){
        android.util.Log.i("Watch4HR",message);
        if(logs.size()==14)logs.removeFirst();
        logs.addLast(message);
    }
    private void publish(){
        long now=SystemClock.elapsedRealtime();
        listener.onState(new State(active,sensorListening,sample.fresh(now)?sample.bpm():0,
            connected.size(),subscribers.size(),sensorEvents,acceptedEvents,sessionStarted,notificationCount,
            hrState,bleState,rawText,diagnostics?TextUtils.join("\n",logs):""));
    }
    private void startBluetooth(int token) {
        try {
        if (bluetoothAdapter == null) { failBle("无 BluetoothAdapter", token); return; }
        if (!bluetoothAdapter.isEnabled()) { failBle("先开启手表蓝牙", token); return; }
        log("isMultipleAdvertisementSupported=" + bluetoothAdapter.isMultipleAdvertisementSupported());
        advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        log("getBluetoothLeAdvertiser=" + (advertiser == null ? "null" : "非 null"));
        if (advertiser == null) { failBle("广播器为空", token); return; }
        gattServer = bluetoothManager.openGattServer(context, callbacks(token));
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
            if (current(token) && !serviceReady) failBle("onServiceAdded 超时", token);
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
        scheduleRecovery();
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
                retry.reset();
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
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
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
                        { serviceReady = true; startAdvertising(token, true); }
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
                    if (subscribers.isEmpty() && serviceReady && advertiseCallback == null)
                        startAdvertising(token, true);
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
                        queueLatest();
                    }
                    if (enabled) stopAdvertisingOnly();
                    else if (subscribers.isEmpty() && serviceReady && advertiseCallback == null)
                        startAdvertising(token, true);
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
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failBle("通知完成失败 status=" + status, token); return;
                    }
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
                if (gattServer.notifyCharacteristicChanged(d, hrCharacteristic, false)) { notificationCount++; return; }
                inFlight = null; log("WARN: notifyCharacteristicChanged=false");
            } catch (SecurityException e) {
                inFlight = null;
                failBle("通知权限异常 " + e, generation);
                return;
            } catch (RuntimeException e) { inFlight = null; log("通知异常：" + e); }
        }
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (!active || !sensorListening || e.sensor.getType() != Sensor.TYPE_HEART_RATE) return;
        long now = SystemClock.elapsedRealtime();
        long eventMs = e.timestamp / 1_000_000;
        sensorEvents++;
        if (e.accuracy > 0) positiveAccuracyEvents++;
        float raw = e.values.length == 0 ? Float.NaN : e.values[0];
        String reason = e.values.length == 0 ? "EMPTY_VALUES：事件无 BPM"
                : sample.reason(raw, e.accuracy, eventMs, now);
        boolean oldSession = eventMs < sensorRegisteredAt;
        // Preserve the original policy: never replay events from a previous session.
        // Report both the validation result and this additional session gate.
        String decision = oldSession ? "OLD_SESSION：时间早于本次开始" : reason;
        if (!oldSession && "OK".equals(reason)) acceptedEvents++;
        if (lastRawDisplay < 0 || now - lastRawDisplay >= 1000) {
            rawText = "raw=" + raw + "  accuracy=" + e.accuracy
                    + "\n事件年龄=" + (now - eventMs) + "ms，窗口=" + futureSkewMs + "ms"
                    + "\n事件/正状态/通过=" + sensorEvents + "/" + positiveAccuracyEvents + "/" + acceptedEvents;
            lastRawDisplay = now;
        }
        if (diagnostics && (lastRawLog < 0 || now - lastRawLog >= 1000)) {
            log("RAW_HR raw=" + raw + " accuracy=" + e.accuracy + " values=" + Arrays.toString(e.values)
                    + " eventNs=" + e.timestamp + " elapsedMs=" + now
                    + " uptimeMs=" + SystemClock.uptimeMillis() + " sessionMs=" + sessionStarted
                    + " ageMs=" + (now - eventMs) + " futureToleranceMs=" + futureSkewMs
                    + " boundedArrivalTime=" + (!oldSession && "OK".equals(reason) && eventMs > now)
                    + " validation=" + reason + " decision=" + decision);
            lastRawLog = now;
        }
        if (oldSession) {
            // A queued old event does not invalidate an otherwise fresh current sample.
            if (!sample.fresh(now)) setHrState(decision + "；见原始事件");
            return;
        }
        if (e.values.length == 0 || !sample.accept(raw, e.accuracy, eventMs, now)) {
            invalidateSample(reason + "，raw=" + raw + " accuracy=" + e.accuracy);
            return;
        }
        hadSample = true;

        setHrState("真实 BPM，accuracy=" + e.accuracy + (eventMs > now ? "（时钟兼容）" : ""));
        if (diagnostics && (sample.bpm() != lastLoggedBpm || now - lastSampleLog >= 30_000)) {
            log("HR bpm=" + sample.bpm() + " accuracy=" + e.accuracy);
            lastLoggedBpm = sample.bpm(); lastSampleLog = now;
        }
        publish();
    }
    @Override public void onAccuracyChanged(Sensor s, int accuracy) {
        if (!active || s.getType() != Sensor.TYPE_HEART_RATE) return;
        if (diagnostics) log("ACCURACY_CHANGED accuracy=" + accuracy);
        if (accuracy <= 0) invalidateSample("ACCURACY_CHANGED：不可靠或无接触，accuracy=" + accuracy);
    }
    private void invalidateSample(String reason) {
        sample.clear(); pending.clear(); hadSample = false;

        if (!hrState.equals(reason) && diagnostics) log(reason);
        setHrState(reason);
    }

    private void closeBle() {
        if (advertiser != null && advertiseCallback != null) {
            try { advertiser.stopAdvertising(advertiseCallback); } catch (SecurityException e) { log("权限异常：" + e); } catch (RuntimeException e) { log("stopAdvertising：" + e); }
        }
        advertiseCallback = null; advertiser = null; advertising = false; serviceReady = false;
        if (gattServer != null) {
            try { gattServer.close(); } catch (SecurityException e) { log("权限异常：" + e); } catch (RuntimeException e) { log("close GATT：" + e); }
        }
        gattServer = null; hrCharacteristic = null;
        connected.clear(); subscribers.clear(); pending.clear(); inFlight = null;
        setConnectedState();
    }

    void stop(){main.post(this::stopInternal);}
    private void stopInternal(){
        if(!active)return;
        active=false;generation++;main.removeCallbacks(tick);main.removeCallbacks(recover);
        pauseSensor();closeBle();setBleState("已停止");setHrState("已停止");
        log("RIDE_STOP");publish();
    }
    void shutdown(){main.post(()->{stopInternal();main.removeCallbacksAndMessages(null);main.getLooper().quitSafely();});}
}
