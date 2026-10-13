package com.watch4.hrbridge;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;

/** User-started ride session. A bound UI is optional; resources belong to this service. */
public final class RideService extends Service {
    static final String ACTION_START="com.watch4.hrbridge.START";
    static final String ACTION_STOP="com.watch4.hrbridge.STOP";
    private static final String CHANNEL="heart_rate_ride";
    private static final int NOTIFICATION_ID=77;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final IBinder binder=new LocalBinder();
    private RideEngine engine;
    private volatile RideEngine.State state=RideEngine.State.idle();
    private SharedPreferences prefs;
    private NotificationManager notifications;
    private PowerManager power;
    private PowerManager.WakeLock cpuLock;
    private volatile boolean riding;
    private boolean destroyed, receiversRegistered;
    private long lastNotification=-15000, lastLockRenew;
    private int batteryStart=-1, batteryNow=-1;
    private boolean charging;
    private String notificationKey="";
    public final class LocalBinder extends Binder { RideService service(){return RideService.this;} }
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            String action=intent.getAction();
            if(BluetoothAdapterAction().equals(action))
                engine.bluetoothChanged(intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE,-1));
            if(Intent.ACTION_BATTERY_CHANGED.equals(action)){
                int level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
                int scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,100);
                batteryNow=level>=0 && scale>0?Math.round(level*100f/scale):-1;
                charging=intent.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;
                if(batteryStart<0 && riding)batteryStart=batteryNow;
            }
            updateCpuLock();
        }
    };
    private static String BluetoothAdapterAction(){return android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED;}
    @Override public void onCreate(){
        super.onCreate();
        prefs=getSharedPreferences("ride",MODE_PRIVATE);
        notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        power=(PowerManager)getSystemService(POWER_SERVICE);
        cpuLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Watch4HR:RideCpu");
        cpuLock.setReferenceCounted(false);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"骑行心率广播",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("骑行运行状态及停止入口");channel.setSound(null,null);
        notifications.createNotificationChannel(channel);
        HandlerThread thread=new HandlerThread("Watch4HR-Worker");thread.start();
        engine=new RideEngine(this,new Handler(thread.getLooper()),s->ui.post(()->{
            if(destroyed)return;
            state=s;
            if(!riding)return;
            updateCpuLock();
            updateNotification(false);
        }));
    }
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?null:intent.getAction();
        if(ACTION_STOP.equals(action)){
            stopRide();return START_NOT_STICKY;
        }
        boolean restart=intent==null && prefs.getBoolean("resume",false);
        if(!ACTION_START.equals(action) && !restart){
            if(!riding)stopSelf();return riding?START_STICKY:START_NOT_STICKY;
        }
        if(Build.VERSION.SDK_INT>30 || checkSelfPermission(Manifest.permission.BODY_SENSORS)!=PackageManager.PERMISSION_GRANTED){
            prefs.edit().putBoolean("resume",false).apply();
            stopRide();return START_NOT_STICKY;
        }
        if(riding)return START_STICKY;
        riding=true;
        prefs.edit().putBoolean("resume",true).apply();
        batteryStart=-1; notificationKey=""; lastNotification=-15000;
        Notification notification=notification();
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION_ID,notification);
        IntentFilter filter=new IntentFilter();
        filter.addAction(BluetoothAdapterAction());
        filter.addAction(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver,filter);
        receiversRegistered=true;
        // Registering the sticky battery broadcast invokes receiver with the initial level.
        if(batteryNow>=0)batteryStart=batteryNow;
        engine.start();
        ui.post(powerWatchdog);
        android.util.Log.i("Watch4HR","FOREGROUND_RIDE_START restart="+restart);
        return START_STICKY;
    }
    boolean isRiding(){return riding;}
    RideEngine.State snapshot(){return state;}
    boolean reliableMode(){return prefs.getBoolean("reliable",true);}
    void setReliableMode(boolean enabled){
        prefs.edit().putBoolean("reliable",enabled).apply();updateCpuLock();updateNotification(true);
    }
    void setDiagnostics(boolean enabled){engine.setDiagnostics(enabled);}
    String powerSummary(){
        String battery=batteryNow<0?"未知":batteryNow+"%";
        String delta=batteryStart<0 || batteryNow<0?"": "，起始 "+batteryStart+"%";
        return (reliableMode()?"锁屏可靠模式":"省电试验模式")
                +"；CPU锁="+(cpuLock.isHeld()?"持有":"释放")
                +"\n电量 "+battery+delta+(charging?"（充电中，不能用于耗电测量）":"");
    }
    private final Runnable powerWatchdog=new Runnable(){public void run(){
        if(!riding || destroyed)return;
        updateCpuLock();updateNotification(false);ui.postDelayed(this,60000);
    }};
    private void updateCpuLock(){
        boolean needed=PowerPolicy.needsCpuLock(riding,reliableMode(),power.isInteractive(),state.sensorListening);
        if(needed){
            long now=SystemClock.elapsedRealtime();
            if(!cpuLock.isHeld() || now-lastLockRenew>=60000){
                // A renewable ten-minute lease limits a leaked lock if the service looper stalls.
                cpuLock.acquire(600000L);lastLockRenew=now;
            }
        }else if(cpuLock.isHeld())cpuLock.release();
    }
    private Notification notification(){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,RideService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String text="连接 "+state.connections+" / 订阅 "+state.subscriptions
                +"；"+(state.bpm>0?"心率有效":"等待有效心率");
        return new Notification.Builder(this,CHANNEL).setSmallIcon(com.watch4.hrbridge.R.drawable.ic_heart)
                .setContentTitle("Watch4 心率广播运行中").setContentText(text)
                .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"停止广播",stop).build()).build();
    }
    private void updateNotification(boolean force){
        if(!riding)return;
        long now=SystemClock.elapsedRealtime();
        String key=state.connections+"/"+state.subscriptions+"/"+(state.bpm>0)+"/"+reliableMode();
        // A stable status needs no repeated notification refresh or per-BPM update.
        if(force || (!key.equals(notificationKey) && now-lastNotification>=15000)){
            notifications.notify(NOTIFICATION_ID,notification());lastNotification=now;notificationKey=key;
        }
    }
    private void stopRide(){
        riding=false;prefs.edit().putBoolean("resume",false).apply();
        ui.removeCallbacks(powerWatchdog);
        if(receiversRegistered){unregisterReceiver(receiver);receiversRegistered=false;}
        engine.stop();
        if(cpuLock.isHeld())cpuLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
        android.util.Log.i("Watch4HR","FOREGROUND_RIDE_STOP");
    }
    @Override public void onDestroy(){
        destroyed=true;riding=false;ui.removeCallbacksAndMessages(null);
        if(receiversRegistered){unregisterReceiver(receiver);receiversRegistered=false;}
        if(cpuLock.isHeld())cpuLock.release();
        engine.shutdown();stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    // stopWithTask=false: swiping the UI does not intentionally stop a ride.
    // Force-stop, reboot and vendor process killing can still terminate Android services.
}
