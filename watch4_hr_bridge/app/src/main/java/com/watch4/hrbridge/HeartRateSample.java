package com.watch4.hrbridge;

/** Pure protocol logic. Samples always originate from the Android sensor. */
final class HeartRateSample {
    static final long MAX_AGE_MS = 30_000;
    private int bpm;
    private long timestampMs = -1;

    boolean accept(float raw, int accuracy, long eventMs, long nowMs) {
        if (!Float.isFinite(raw) || raw < 1 || raw > 65535 || accuracy <= 0
                || eventMs < 0 || eventMs > nowMs || nowMs - eventMs >= MAX_AGE_MS) {
            clear();
            return false;
        }
        bpm = Math.round(raw);
        timestampMs = eventMs;
        return true;
    }

    boolean fresh(long nowMs) {
        return timestampMs >= 0 && nowMs >= timestampMs && nowMs - timestampMs < MAX_AGE_MS;
    }

    int bpm() { return bpm; }
    void clear() { timestampMs = -1; bpm = 0; }

    byte[] payload(long nowMs) {
        if (!fresh(nowMs)) return null;
        // Contact is not independently measured here, so contact bits stay unsupported.
        return bpm <= 255 ? new byte[]{0, (byte) bpm}
                : new byte[]{1, (byte) bpm, (byte) (bpm >>> 8)};
    }
}
