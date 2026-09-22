package com.axalotl.fabrolia.common.regions;

/**
 * Narrow duck on {@code Connection}: publish a task to its ordered Netty event
 * loop without running connection callbacks on the publishing thread.
 */
public interface FabroliaConnectionEventLoop {

    /**
     * Returns only after the task has entered the event-loop FIFO. When a region
     * generation exists, the offer retains that generation's scheduler permit;
     * flat runtime uses ordinary executor ownership. The task is never invoked
     * inline by this method. Connection-state bodies still own their receipt and
     * nested success cut, which inherit retained causal authority when present.
     */
    void fabrolia$enqueueEventLoopTask(Runnable task);
}
