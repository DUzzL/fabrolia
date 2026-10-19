package com.axalotl.fabrolia.common.regions;

/**
 * An A5 listener-lane entry that is never decoded from a client: Main/command/chat
 * continuations and routed player mutations. They model server-executor work or
 * owed mutations with their own lifetime rules, so vanilla's PacketProcessor gates
 * for decoded packets ({@code shouldHandleMessage}, the shutdown close) do not
 * apply to them.
 */
interface InProcessListenerPacket {
}
