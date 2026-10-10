/**
 * Region and island execution, with chunk access governed by explicit ownership.
 *
 * <p>The entry points are {@link com.axalotl.fabrolia.common.regions.RegionScheduler},
 * {@link com.axalotl.fabrolia.common.regions.RegionizedServer}, and
 * {@link com.axalotl.fabrolia.common.regions.IslandTickUnit}. The worker pool admits
 * tasks; the island registry owns topology; epoch domains and island clocks own
 * simulation time. Claims and fences must precede native chunk/entity access.
 *
 * <p>Supporting subsystems are grouped by class prefix: AsyncIslandPlayer for
 * player transport, Function for datapack planning, ChunkDelivery for client
 * publication, NetherPortal for prepared transfers, and IslandBlock/IslandScheduled
 * for ordered world phases. Their shared package permits internal coordination;
 * public bridge names are also consumed by mixins and external integrations.
 *
 * <p>See docs/CODEMAP.md for phase order and docs/ASYNC-ISLANDS.md for invariants.
 */
package com.axalotl.fabrolia.common.regions;
