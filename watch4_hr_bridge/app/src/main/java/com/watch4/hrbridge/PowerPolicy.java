package com.watch4.hrbridge;

final class PowerPolicy {
    static boolean needsCpuLock(boolean riding, boolean reliable, boolean screenOn, boolean listening, long stateAgeMs) {
        return riding && reliable && !screenOn && listening && stateAgeMs>=0 && stateAgeMs<=10000;
    }
}
