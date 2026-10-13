package com.watch4.hrbridge;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.*;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Small-screen controller. Closing this page only unbinds the UI, never the ride. */
public final class MainActivity extends Activity {
    private static final int BODY_REQUEST=100;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private RideService service;
    private boolean bound, visible, awaitingStart;
    private TextView bpm, hr, ble, link, power, details;
    private Button start, stop;
    private CheckBox reliable, debug;
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            service=((RideService.LocalBinder)binder).service();
            reliable.setChecked(service.reliableMode());
            service.setDiagnostics(visible && debug.isChecked());
            render();
        }
        @Override public void onServiceDisconnected(ComponentName name){service=null;}
    };
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int sp){
        TextView t=new TextView(this);t.setText(value);t.setTextColor(Color.WHITE);
        t.setTextSize(sp);t.setPadding(0,dp(5),0,dp(5));return t;
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Color.rgb(12,17,24));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(8),dp(16),dp(18));scroll.addView(root);
        TextView title=text("WATCH4 HR RIDE 0.5",16);title.setGravity(Gravity.CENTER);root.addView(title);
        bpm=text("-- BPM",34);bpm.setTextColor(Color.rgb(116,224,174));
        bpm.setTypeface(Typeface.DEFAULT,Typeface.BOLD);bpm.setGravity(Gravity.CENTER);root.addView(bpm);
        hr=text("心率：未开始",13);root.addView(hr);ble=text("BLE：未开始",13);root.addView(ble);
        link=text("连接 0 / 订阅 0",13);root.addView(link);
        start=new Button(this);start.setText("开始骑行广播");root.addView(start);
        stop=new Button(this);stop.setText("停止广播");root.addView(stop);
        reliable=new CheckBox(this);reliable.setText("锁屏可靠模式");reliable.setTextColor(Color.WHITE);
        reliable.setChecked(getSharedPreferences("ride",MODE_PRIVATE).getBoolean("reliable",true));root.addView(reliable);
        root.addView(text("默认开启。关闭后减少CPU保活，屏灭更新需实测。",11));
        power=text("",11);root.addView(power);
        Button settings=new Button(this);settings.setText("查看电池优化设置");root.addView(settings);
        settings.setOnClickListener(v->{
            try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}
            catch(ActivityNotFoundException e){startActivity(new Intent(Settings.ACTION_SETTINGS));}
        });
        debug=new CheckBox(this);debug.setText("显示诊断");debug.setTextColor(Color.LTGRAY);root.addView(debug);
        details=text("",11);details.setVisibility(View.GONE);root.addView(details);
        root.addView(text("开始后可返回表盘、锁屏。通知中也可停止。\n强行停止或重启手表后需要手动开始。",11));
        start.setOnClickListener(v->requestStart());
        stop.setOnClickListener(v->{
            awaitingStart=false;
            startService(new Intent(this,RideService.class).setAction(RideService.ACTION_STOP));
        });
        reliable.setOnCheckedChangeListener((v,checked)->{
            getSharedPreferences("ride",MODE_PRIVATE).edit().putBoolean("reliable",checked).apply();
            if(service!=null)service.setReliableMode(checked);
        });
        debug.setOnCheckedChangeListener((v,checked)->{
            details.setVisibility(checked?View.VISIBLE:View.GONE);
            if(service!=null)service.setDiagnostics(visible && checked);
        });
        setContentView(scroll);
    }
    private void requestStart(){
        if(Build.VERSION.SDK_INT>30){hr.setText("本版仅针对 Android 8–11，目标 API 30");return;}
        if(checkSelfPermission(Manifest.permission.BODY_SENSORS)!=PackageManager.PERMISSION_GRANTED){
            awaitingStart=true;requestPermissions(new String[]{Manifest.permission.BODY_SENSORS},BODY_REQUEST);return;
        }
        launchRide();
    }
    private void launchRide(){
        awaitingStart=false;
        try{startForegroundService(new Intent(this,RideService.class).setAction(RideService.ACTION_START));}
        catch(RuntimeException e){hr.setText("启动失败："+e.getClass().getSimpleName());}
    }
    @Override public void onRequestPermissionsResult(int id,String[] permissions,int[] result){
        super.onRequestPermissionsResult(id,permissions,result);
        if(id==BODY_REQUEST){
            if(result.length>0 && result[0]==PackageManager.PERMISSION_GRANTED && visible && awaitingStart)launchRide();
            else{awaitingStart=false;hr.setText("授权后可点击开始");}
        }
    }
    private final Runnable refresh=new Runnable(){public void run(){
        if(!visible)return;render();ui.postDelayed(this,1000);
    }};
    private void render(){
        if(service==null)return;
        RideEngine.State s=service.snapshot();
        bpm.setText(s.bpm>0?s.bpm+" BPM":"-- BPM");
        hr.setText("心率："+s.hr);ble.setText("BLE："+s.ble);
        link.setText("连接 "+s.connections+" / 订阅 "+s.subscriptions);
        boolean running=service.isRiding();
        start.setEnabled(!running);stop.setEnabled(running);
        long minutes=s.startedMs==0?0:(SystemClock.elapsedRealtime()-s.startedMs)/60000;
        power.setText(service.powerSummary()+"\n运行 "+minutes+" 分钟，已提交通知 "+s.notificationCount+" 条");
        if(debug.isChecked())details.setText(s.raw+"\n事件/通过="+s.events+"/"+s.accepted+"\n"+s.logs);
    }
    @Override protected void onStart(){
        super.onStart();visible=true;
        bound=bindService(new Intent(this,RideService.class),connection,BIND_AUTO_CREATE);ui.post(refresh);
    }
    @Override protected void onStop(){
        visible=false;ui.removeCallbacks(refresh);
        if(service!=null)service.setDiagnostics(false);
        if(bound){unbindService(connection);bound=false;}service=null;
        super.onStop();
    }
    @Override protected void onDestroy(){ui.removeCallbacksAndMessages(null);super.onDestroy();}
}


