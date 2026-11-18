package com.axalotl.fabrolia.common.regions;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins every venue that resolves a player's open menu or block-break progress
 * ({@link PlayerRemoteState}): island admission refuses the player, and each Main
 * venue runs the vanilla body under the global fence when that state points into
 * another island than the one Main claims.
 */
final class PlayerRemoteStateWiringTest {

    @Test
    void islandAdmissionRefusesAPlayerWithRemoteState() throws Exception {
        String a5 = source("common/regions/AsyncIslandPlayerTicks.java");
        String eligible = section(a5, "private static boolean eligible(ServerPlayer player",
                "private static boolean islandVenueAvailable(");
        int portal = eligible.indexOf("guard.fabrolia$isPortalPending()");
        int remote = eligible.indexOf(
                "if (PlayerRemoteState.reachesOutsideOwnIsland(player)) return false;", portal);
        int loaded = eligible.indexOf("player.connection.hasClientLoaded()", remote);
        assertTrue(portal >= 0 && remote > portal && loaded > remote,
                "eligible() is shared by tryRunEarly, the Main listener gate and venue reattach");
        String cause = section(a5, "private static String ineligibleCause(",
                "private static String earlyAdmissionFallbackCause(");
        assertTrue(cause.contains(
                "if (PlayerRemoteState.reachesOutsideOwnIsland(player)) return \"state in another island\";"));
        String early = section(a5, "public static boolean tryRunEarly(Region region, ServerPlayer player)",
                "PlayerState state = state(player);");
        assertTrue(early.contains("|| !eligible(player, level, kind)) {"));
    }

    @Test
    void mainSyncDrainServesRemotePlayerTreesAsFencedTasks() throws Exception {
        String scheduler = source("common/regions/RegionScheduler.java");
        String unbatched = section(scheduler, "if (!MAIN_CLAIM_BATCHING) {",
                "mainSyncDrainDepth++;");
        assertTrue(unbatched.contains("runOneMainSync(fenceRemotePlayerState(queued));"),
                "unbatched drain");
        String batched = section(scheduler, "private static boolean drainMainSyncBatched(",
                "buckets.add(mainClaimOwnerOf(queued.entity()), queued);");
        int poll = batched.indexOf("while ((queued = MAIN_SYNC.poll()) != null) {");
        int route = batched.indexOf("queued = fenceRemotePlayerState(queued);", poll);
        int fenced = batched.indexOf("if (queued.allIslandsFenced()) {", route);
        assertTrue(poll >= 0 && route > poll && fenced > route,
                "the poll decides before the fenced branch and before any bucket");
        String route0 = section(scheduler, "private static MainSyncTask fenceRemotePlayerState(",
                "private static boolean drainMainSyncTicks(DrainMode mode)");
        assertTrue(route0.contains("PlayerRemoteState.treeReachesOutsideIsland(queued.entity())"));
        assertTrue(route0.contains("return queued.withAllIslandsFenced();"));
        String fencedRun = section(scheduler, "private static void runOneMainSync(MainSyncTask queued)",
                "private static final ThreadLocal<java.util.ArrayList<MainSyncTask>> DEFERRED_TAILS");
        assertTrue(fencedRun.contains("if (queued.allIslandsFenced()) {")
                && fencedRun.contains("AsyncIslandPlayerTicks.runFencedMainMutation("));
    }

    @Test
    void mainPlayerBodiesEscalateToTheFenceAfterTheirClaim() throws Exception {
        String scheduler = source("common/regions/RegionScheduler.java");
        assertTrue(section(scheduler, "private static void runMainSyncEntityTask(MainSyncTask queued)",
                "private static void runOneMainSync(")
                .contains("runPlayerTickWithMainClaim(e, queued, null, PlayerRemoteState.FENCE_ENTITY_TICK);"));
        String body = section(scheduler, "private static void runClaimedEntityBody(",
                "private static void runWithMainEntityClaim(Entity entity, Runnable body,");
        int test = body.indexOf(
                "remoteStateFence != null && PlayerRemoteState.treeReachesOutsideIsland(entity)");
        int fence = body.indexOf(
                "AsyncIslandPlayerTicks.runFencedMainMutation(remoteStateFence, body);",
                test);
        int plain = body.indexOf("runSuccessBodyIfHealthy(body);", fence);
        assertTrue(test >= 0 && fence > test && plain > fence);
        String claim = section(scheduler, "private static void runWithMainEntityClaim(Entity entity, Runnable body,",
                "Publishes a failed Main operation into the generation's first-wins fatal slot");
        assertEquals(2, count(claim, "runClaimedEntityBody(entity, body, remoteStateFence);"),
                "owner-gap body and claimed body");
        int revalidate = claim.indexOf("&& !owner.retired) {");
        int claimed = claim.indexOf("runClaimedEntityBody(entity, body, remoteStateFence);", revalidate);
        assertTrue(revalidate >= 0 && claimed > revalidate,
                "the escalation decision follows the claim wait and its revalidation");
    }

    @Test
    void mainListenerTicksUseThePlayerTickClaim() throws Exception {
        String a5 = source("common/regions/AsyncIslandPlayerTicks.java");
        String direct = section(a5, "public static void runMainListenerTick(",
                "private static boolean cancelDirectMainListenerTick(");
        assertTrue(direct.contains("RegionScheduler.runPlayerTickWithMainClaim(player, () -> {"));
        assertTrue(direct.contains("PlayerRemoteState.FENCE_LISTENER_TICK);"));
        assertTrue(!direct.contains("runWithMainEntityClaim("));
        String deferred = section(a5, "lease.execute(() -> RegionScheduler.runPlayerTickWithMainClaim(player, () -> {",
                "public static boolean deferBlockingDisconnect(");
        assertTrue(deferred.contains("}, null, PlayerRemoteState.FENCE_LISTENER_TICK));"));
    }

    @Test
    void menuPacketsTakeTheFenceForARemoteMenu() throws Exception {
        String mixin = source("common/mixin/server/PlayerInteractionOwnerMixin.java");
        for (String handler : new String[]{
                "fabrolia$ownContainerClick", "fabrolia$ownButtonClick",
                "fabrolia$ownSelectTrade", "fabrolia$ownPlaceRecipe",
                "fabrolia$ownRenameItem", "fabrolia$ownSetBeacon",
                "fabrolia$ownContainerClose"}) {
            String wrap = section(mixin, "private void " + handler + "(", "@WrapMethod");
            assertTrue(wrap.contains("runVanillaPacketBody(this::withMenuOwner,"), handler);
        }
        String menu = section(mixin, "private void withMenuOwner(Runnable body) {",
                "private void withPlayerOwner(Runnable body) {");
        int raw = menu.indexOf("!fabrolia$rawEntry()");
        int remote = menu.indexOf(".reachesOutsideOwnIsland(this.player)", raw);
        int replay = menu.indexOf("AsyncIslandPlayerTicks.inPacketReplay(this.player)", remote);
        int fail = menu.indexOf("throw AsyncIslandPlayerTicks.packetInteractionFailure(", replay);
        int fence = menu.indexOf("AsyncIslandPlayerTicks.runFencedMainMutation(", fail);
        assertTrue(menu.contains("PlayerRemoteState.FENCE_MENU_PACKET, body);"));
        int local = menu.indexOf("withPlayerOwner(body);", fence);
        assertTrue(raw >= 0 && remote > raw && replay > remote && fail > replay
                && fence > fail && local > fence);
    }

    @Test
    void menuAnchorIsRecordedWhereTheMenuIsOpenedAndValidated() throws Exception {
        String player = source("common/mixin/server/ServerPlayerMixin.java");
        assertTrue(player.contains("com.axalotl.fabrolia.common.regions.PlayerRemoteState.Access {"));
        String open = section(player,
                "method = \"initMenu(Lnet/minecraft/world/inventory/AbstractContainerMenu;)V\"",
                "fabrolia$anchorValidatedMenu");
        assertTrue(open.contains("At(\"HEAD\")") && open.contains("fabrolia$recordMenuAnchor(menu);"));
        String valid = section(player, "method = {\"tick()V\", \"doTick()V\"},",
                "@WrapMethod(method = \"teleport");
        assertTrue(valid.contains("stillValid(Lnet/minecraft/world/entity/player/Player;)Z"));
        assertTrue(valid.contains("if (valid && menu != this.inventoryMenu) fabrolia$recordMenuAnchor(menu);"));
        String mixins = Files.readString(resource("fabrolia.common.mixins.json"));
        assertTrue(mixins.contains("\"server.ServerPlayerGameModeDestroyAccessor\""));
        String accessor = source("common/mixin/server/ServerPlayerGameModeDestroyAccessor.java");
        for (String field : new String[]{"isDestroyingBlock", "destroyPos",
                "hasDelayedDestroy", "delayedDestroyPos"}) {
            assertTrue(accessor.contains("@Accessor(\"" + field + "\")"), field);
        }
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) n++;
        return n;
    }

    private static String section(String text, String startNeedle, String endNeedle) {
        int start = text.indexOf(startNeedle);
        int end = text.indexOf(endNeedle, Math.max(0, start + startNeedle.length()));
        assertTrue(start >= 0 && end > start,
                () -> "source section not found: " + startNeedle + " .. " + endNeedle);
        return text.substring(start, end);
    }

    private static Path resource(String name) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path direct = root.resolve("src/main/resources").resolve(name);
            Path common = root.resolve("common/src/main/resources").resolve(name);
            if (Files.isRegularFile(direct)) return direct;
            if (Files.isRegularFile(common)) return common;
            root = root.getParent();
        }
        throw new IllegalStateException("cannot locate resource: " + name);
    }

    private static String source(String relative) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia").resolve(relative);
            Path common = root.resolve("common/src/main/java/com/axalotl/fabrolia")
                    .resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(common)) return Files.readString(common);
            root = root.getParent();
        }
        throw new IllegalStateException("cannot locate source: " + relative);
    }
}
