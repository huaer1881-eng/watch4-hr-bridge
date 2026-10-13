package com.watch4.hrbridge;

/** Pure protocol logic. Samples always originate from the Android sensor. */
final class HeartRateSample {
    static final long MAX_AGE_MS = 30_000;
    private int bpm;
    private long timestampMs = -1;

    static String rejectionReason(float raw, int accuracy, long eventMs, long nowMs) {
        if (accuracy == -1) return "NO_CONTACT：系统报告无接触";
        if (accuracy <= 0) return "UNRELIABLE：系统报告不可靠";
        if (!Float.isFinite(raw)) return "NONFINITE：BPM 非有限值";
        if (raw < 1 || raw > 65535) return "INVALID_BPM：原始 BPM 超出范围";
        if (eventMs < 0) return "INVALID_TIME：时间戳为负";
        if (eventMs > nowMs) return "FUTURE_TIME：事件时间晚于系统时间";
        if (nowMs - eventMs >= MAX_AGE_MS) return "STALE_TIME：事件已过期";
        return "OK";
    }

    boolean accept(float raw, int accuracy, long eventMs, long nowMs) {
        if (!"OK".equals(rejectionReason(raw, accuracy, eventMs, nowMs))) {
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
