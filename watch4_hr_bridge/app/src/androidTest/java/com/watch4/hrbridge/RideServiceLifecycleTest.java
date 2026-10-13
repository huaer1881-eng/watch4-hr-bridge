package com.watch4.hrbridge;

import android.Manifest;
import android.app.NotificationManager;
import android.content.*;
import android.os.IBinder;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RideServiceLifecycleTest {
    private Context context;
    private RideService service;
    private ServiceConnection connection;
    private ActivityScenario<MainActivity> activity;
    @Before public void setup() throws Exception {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(context.getPackageName(),Manifest.permission.BODY_SENSORS);
        activity=ActivityScenario.launch(MainActivity.class);
        CountDownLatch bound=new CountDownLatch(1);
        AtomicReference<RideService> reference=new AtomicReference<>();
        connection=new ServiceConnection(){
            public void onServiceConnected(ComponentName name,IBinder binder){
                reference.set(((RideService.LocalBinder)binder).service());bound.countDown();
            }
            public void onServiceDisconnected(ComponentName name){}
        };
        assertTrue(context.bindService(new Intent(context,RideService.class),connection,Context.BIND_AUTO_CREATE));
        assertTrue(bound.await(8,TimeUnit.SECONDS));service=reference.get();
        context.startForegroundService(new Intent(context,RideService.class).setAction(RideService.ACTION_START));
        await(()->service.isRiding() && service.snapshot().running);
    }
    @After public void cleanup(){
        if(service!=null)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->
                service.onStartCommand(new Intent(context,RideService.class).setAction(RideService.ACTION_STOP),0,0));
        if(connection!=null)context.unbindService(connection);
        if(activity!=null)activity.close();
    }
    interface Condition { boolean ready(); }
    private void await(Condition c){
        long limit=SystemClock.elapsedRealtime()+8000;
        while(!c.ready() && SystemClock.elapsedRealtime()<limit)SystemClock.sleep(100);
        assertTrue("Timed out waiting for service state",c.ready());
    }
    private StatusBarNotification notification(){
        NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        for(StatusBarNotification n:manager.getActiveNotifications())if(n.getId()==77)return n;
        return null;
    }
    @Test public void destroyingAndReopeningUiDoesNotStopForegroundRide(){
        await(()->notification()!=null);
        activity.close();activity=null;
        SystemClock.sleep(1200);
        assertTrue(service.isRiding());assertTrue(service.snapshot().running);
        assertNotNull(notification());
        activity=ActivityScenario.launch(MainActivity.class);
        assertTrue(service.isRiding());assertTrue(service.snapshot().running);
    }
    @Test public void notificationStopRemovesForegroundAndStopsEngine() throws Exception {
        await(()->notification()!=null);
        StatusBarNotification n=notification();assertNotNull(n.getNotification().actions);
        n.getNotification().actions[0].actionIntent.send();
        await(()->!service.isRiding() && !service.snapshot().running && notification()==null);
        assertEquals(0,service.snapshot().subscriptions);
        assertFalse(service.snapshot().sensorListening);
        assertFalse(context.getSharedPreferences("ride",Context.MODE_PRIVATE).getBoolean("resume",true));
    }
}
