package com.axalotl.fabrolia.common.utils;

public interface FabroliaPalettedContainerLock {
    void fabrolia$readLock();

    void fabrolia$readUnlock();

    void fabrolia$writeLock();

    void fabrolia$writeUnlock();
}
