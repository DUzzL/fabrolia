package com.axalotl.fabrolia.common.regions;

/** Mixed into {@code ServerLevel}: vanilla's {@code shouldTickBlocksAt(long)} for ONE
 *  single-threaded pass, answered from one completed-simulation snapshot captured
 *  when this is called (the view {@code DistanceManager.inBlockTickingRange} reads). */
public interface BlockTickingPassAccess {
    java.util.function.LongPredicate fabrolia$blockTickingPass();
}
