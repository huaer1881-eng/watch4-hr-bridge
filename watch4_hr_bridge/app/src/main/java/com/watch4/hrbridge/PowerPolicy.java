package com.watch4.hrbridge;

final class PowerPolicy {
    static boolean needsCpuLock(boolean riding, boolean reliable, boolean screenOn, boolean listening) {
        return riding && reliable && !screenOn && listening;
    }
}
