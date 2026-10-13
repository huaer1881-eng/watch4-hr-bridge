package com.watch4.hrbridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class HeartRateSampleTest {
    @Test public void encodesUint8AndUint16LittleEndian() {
        HeartRateSample s = new HeartRateSample();
        assertTrue(s.accept(255, 3, 1000, 1000));
        assertArrayEquals(new byte[]{0, (byte) 255}, s.payload(1000));
        assertTrue(s.accept(256, 3, 1000, 1000));
        assertArrayEquals(new byte[]{1, 0, 1}, s.payload(1000));
        assertTrue(s.accept(65535, 3, 1000, 1000));
        assertArrayEquals(new byte[]{1, (byte) 255, (byte) 255}, s.payload(1000));
    }
    @Test public void invalidSamplesRemovePreviousValue() {
        for (float raw : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 65536}) {
            HeartRateSample s = new HeartRateSample();
            assertTrue(s.accept(72, 3, 1000, 1000));
            assertFalse(s.accept(raw, 3, 1100, 1100));
            assertNull(s.payload(1100));
        }
    }
    @Test public void rejectsNoContactAndUnreliableAccuracy() {
        HeartRateSample s = new HeartRateSample();
        assertFalse(s.accept(72, -1, 1000, 1000));
        assertFalse(s.accept(72, 0, 1000, 1000));
        assertTrue(s.accept(72, 1, 1000, 1000));
    }
    @Test public void enforcesTimestampFreshnessAndReset() {
        HeartRateSample s = new HeartRateSample();
        assertTrue(s.accept(72, 3, 1000, 1000));
        assertNotNull(s.payload(30999));
        assertNull(s.payload(31000));
        assertFalse(s.accept(72, 3, 1000, 31000));
        assertFalse(s.accept(72, 3, 1100, 1000));
        assertTrue(s.accept(72, 3, 1000, 1000));
        s.clear();
        assertNull(s.payload(1000));
    }
}
