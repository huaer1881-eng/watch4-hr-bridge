package com.watch4.hrbridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class RidePolicyTest {
    @Test public void cpuLockIsLimitedToActiveReliableScreenOffSampling() {
        assertTrue(PowerPolicy.needsCpuLock(true,true,false,true,0));
        assertFalse(PowerPolicy.needsCpuLock(false,true,false,true,0));
        assertFalse(PowerPolicy.needsCpuLock(true,false,false,true,0));
        assertFalse(PowerPolicy.needsCpuLock(true,true,true,true,0));
        assertFalse(PowerPolicy.needsCpuLock(true,true,false,false,0));
    }
    @Test public void staleWorkerCannotJustifyCpuLockRenewal() {
        assertTrue(PowerPolicy.needsCpuLock(true,true,false,true,10000));
        assertFalse(PowerPolicy.needsCpuLock(true,true,false,true,10001));
        assertFalse(PowerPolicy.needsCpuLock(true,true,false,true,-1));
    }
    @Test public void repeatedBleFailuresBackOffAndRemainCapped() {
        RetryBackoff b=new RetryBackoff();
        assertEquals(2000,b.nextDelayMs());assertEquals(5000,b.nextDelayMs());
        assertEquals(15000,b.nextDelayMs());assertEquals(30000,b.nextDelayMs());
        for(int i=0;i<1000;i++)assertEquals(60000,b.nextDelayMs());
        b.reset();assertEquals(2000,b.nextDelayMs());
    }
}
