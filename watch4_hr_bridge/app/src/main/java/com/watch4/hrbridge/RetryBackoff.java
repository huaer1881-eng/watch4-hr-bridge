package com.watch4.hrbridge;

/** Bound repeated BLE recovery attempts without a tight restart loop. */
final class RetryBackoff {
    private static final long[] DELAYS = {2000, 5000, 15000, 30000, 60000};
    private int attempt;
    long nextDelayMs() {
        int index=attempt;
        if(attempt<DELAYS.length-1)attempt++;
        return DELAYS[index];
    }
    void reset() { attempt=0; }
}
