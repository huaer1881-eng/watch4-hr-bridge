package com.watch4.hrbridge;

/** Pure protocol logic. Samples always originate from the Android sensor. */
final class HeartRateSample {
    static final long MAX_AGE_MS = 30_000;
    // Target-device workaround for the observed event age of -1800ms.
    // Larger clock errors remain rejected; the default policy stays strict.
    static final long MAX_FUTURE_SKEW_MS = 2500;
    private final long futureSkewMs;
    private int bpm;
    private long timestampMs = -1;

    HeartRateSample() { this(0); }

    HeartRateSample(long futureSkewMs) {
        if (futureSkewMs < 0 || futureSkewMs > MAX_FUTURE_SKEW_MS)
            throw new IllegalArgumentException("Invalid future clock tolerance");
        this.futureSkewMs = futureSkewMs;
    }

    static String rejectionReason(float raw, int accuracy, long eventMs, long nowMs) {
        return rejectionReason(raw, accuracy, eventMs, nowMs, 0);
    }

    String reason(float raw, int accuracy, long eventMs, long nowMs) {
        return rejectionReason(raw, accuracy, eventMs, nowMs, futureSkewMs);
    }

    private static String rejectionReason(float raw, int accuracy, long eventMs, long nowMs, long toleranceMs) {
        if (accuracy == -1) return "NO_CONTACT：系统报告无接触";
        if (accuracy <= 0) return "UNRELIABLE：系统报告不可靠";
        if (!Float.isFinite(raw)) return "NONFINITE：BPM 非有限值";
        if (raw < 1 || raw > 65535) return "INVALID_BPM：原始 BPM 超出范围";
        if (eventMs < 0) return "INVALID_TIME：时间戳为负";
        if (nowMs < 0) return "INVALID_TIME：系统时间为负";
        if (eventMs > nowMs && eventMs - nowMs > toleranceMs)
            return "FUTURE_TIME：事件时间超过兼容窗口";
        if (nowMs - eventMs >= MAX_AGE_MS) return "STALE_TIME：事件已过期";
        return "OK";
    }

    boolean accept(float raw, int accuracy, long eventMs, long nowMs) {
        if (!"OK".equals(reason(raw, accuracy, eventMs, nowMs))) {
            clear();
            return false;
        }
        bpm = Math.round(raw);
        // A small future skew cannot extend freshness into the future.
        // Keep the raw timestamp in diagnostics; use arrival time only in this bounded case.
        timestampMs = Math.min(eventMs, nowMs);
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
