package com.watch4.hrbridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class RidePolicyTest {
    @Test public void cpuLockIsLimitedToActiveReliableScreenOffSampling() {
        assertTrue(PowerPolicy.needsCpuLock(true,true,false,true));
        assertFalse(PowerPolicy.needsCpuLock(false,true,false,true));
        assertFalse(PowerPolicy.needsCpuLock(true,false,false,true));
        assertFalse(PowerPolicy.needsCpuLock(true,true,true,true));
        assertFalse(PowerPolicy.needsCpuLock(true,true,false,false));
    }
    @Test public void repeatedBleFailuresBackOffAndRemainCapped() {
        RetryBackoff b=new RetryBackoff();
        assertEquals(2000,b.nextDelayMs());assertEquals(5000,b.nextDelayMs());
        assertEquals(15000,b.nextDelayMs());assertEquals(30000,b.nextDelayMs());
        for(int i=0;i<1000;i++)assertEquals(60000,b.nextDelayMs());
        b.reset();assertEquals(2000,b.nextDelayMs());
    }
}
