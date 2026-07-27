package com.axalotl.fabrolia.common.regions;

/**
 * Control-flow signal for off-main chunk-packet builds (2026-07-13, FastUtil
 * iterator race): the chunk's owner island claim could not be taken within the
 * bounded try window, so the caller must fall back to the main-thread build
 * (which claims via {@code IslandCommands.mainClaimScoped} and can wait
 * safely). Stackless singleton — this is an expected, silent outcome under
 * load, not an error.
 */
public final class OwnerBusyException extends RuntimeException {

    public static final OwnerBusyException INSTANCE = new OwnerBusyException();

    private OwnerBusyException() {
        super("chunk packet build: owner island busy", null, false, false);
    }
}
