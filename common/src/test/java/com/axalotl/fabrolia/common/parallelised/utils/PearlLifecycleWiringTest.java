package com.axalotl.fabrolia.common.parallelised.utils;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-level pins for the owner-wide pearl-loss fixes. */
class PearlLifecycleWiringTest {

    @Test
    void ownerRegistryUsesStableIdentityAndSaveCapturesEverySuccessfulIdentity() throws Exception {
        String text = source("common/mixin/server/ServerPlayerMixin.java");
        assertTrue(text.contains("new com.axalotl.fabrolia.common.parallelised.utils.ConcurrentIdentitySet<>()"));
        assertFalse(text.contains("ConcurrentHashMap.newKeySet()"),
                "Entity hashCode changes with its staged cross-dimension numeric id");

        int live = text.indexOf("if (pearl.isRemoved()) continue;");
        int save = text.indexOf("pearl.save(child)", live);
        int dimension = text.indexOf("child.store(\"ender_pearl_dimension\"", save);
        int capture = text.indexOf("PearlLogoutSaveCapture", dimension);
        assertTrue(live >= 0 && save > live && dimension > save && capture > dimension,
                "every successfully saved identity must enter the exact logout snapshot");
        assertFalse(text.substring(text.indexOf("fabrolia$saveEnderPearlsAtomically"), capture)
                        .contains("savedUuids"),
                "save-time UUID first-wins can choose a stale MC-306936 ghost and break "
                        + "identity-exact UWP coupling");
    }

    @Test
    void playerdataLoadPreflightsOwnerAndWrapsOnlyTheFreshLoadProcessor() throws Exception {
        String text = source("common/mixin/server/ServerPlayerMixin.java");
        int owner = text.indexOf("EntityReference.read(input, \"Owner\")");
        int lookup = text.indexOf("PearlLoadRecovery\n                        .loadedEntity(savedLevel", owner);
        int migrated = text.indexOf(".migratedPreexisting(self, candidate)", lookup);
        int wrap = text.indexOf("private net.minecraft.world.entity.Entity fabrolia$cleanRejectedPearlLoad");
        int process = text.indexOf("processor.process(candidate)", wrap);
        int cleanup = text.indexOf(".rejected(serverLevel, candidate)", process);

        assertTrue(owner >= 0 && lookup > owner && migrated > lookup,
                "same-level and owner-validated migrated canonicals must be reused before deserialization");
        assertTrue(text.contains("EntityType;loadEntityRecursive(")
                        && wrap >= 0 && process > wrap && cleanup > process,
                "cleanup must be scoped to a fresh playerdata candidate rejected by vanilla's processor");
        assertTrue(text.substring(process, cleanup).contains("accepted == null"));

        String recovery = source("common/parallelised/utils/PearlLoadRecovery.java");
        assertTrue(recovery.contains("ProjectileOwnerAccessor")
                        && recovery.contains("fabrolia$getOwnerReference()"),
                "owner validation must survive owner/projectile being in different dimensions");
        assertTrue(recovery.contains("LoadedPearlIndexAccess")
                        && recovery.contains("fabrolia$loadedPearl(uuid)"),
                "HIDDEN canonical entities must be visible to restore recovery");
    }

    @Test
    void loadedIndexBeginsAfterSuccessfulPESMAddAndEndsByIdentity() throws Exception {
        String manager = source("common/mixin/server/PersistentEntitySectionManagerMixin.java");
        int hook = manager.indexOf("fabrolia$onAddEntity");
        int accepted = manager.indexOf("Boolean.TRUE.equals(cir.getReturnValue())", hook);
        int add = manager.indexOf("fabrolia$indexLoadedPearl(pearl)", accepted);
        assertTrue(hook >= 0 && accepted > hook && add > accepted,
                "a rejected clone must never pollute the hidden-inclusive loaded index");

        String callback = source("common/mixin/server/PersistentEntitySectionManagerCallbackMixin.java");
        assertTrue(callback.contains("fabrolia$unindexLoadedPearl(pearl)"));
        assertTrue(manager.contains("fabrolia$loadedPearls.remove(pearl.getUUID(), pearl)"),
                "source/destination overlap requires UUID plus exact-object removal");
        assertTrue(callback.contains("storage.getSection(sectionKey) == section")
                        && callback.contains("candidate == expected")
                        && callback.contains("!this.fabrolia$onRemoveFired.get()"),
                "a stale C2ME index entry must not suppress a real playerdata restore");
        assertTrue(callback.contains("fabrolia$pearlSectionLock")
                        && callback.contains("!lock.tryLock()")
                        && callback.contains("fabrolia$afterMove()"),
                "pearl movement and the residency proof must share a nonblocking lookup lock");
        assertFalse(callback.contains("fabrolia$sectionMoveSequence")
                        || callback.contains("new java.util.concurrent.atomic.AtomicLong"),
                "the pearl-only proof must not allocate/CAS an AtomicLong for every entity");
        int pearlCtor = callback.indexOf("this.entity instanceof");
        int lockCtor = callback.indexOf("this.fabrolia$pearlSectionLock =", pearlCtor);
        assertTrue(pearlCtor >= 0 && lockCtor > pearlCtor,
                "the residency lock must be allocated only for thrown pearls");
        assertTrue(manager.contains("fabrolia$hasLoadedPearlInChunk")
                        && source("common/utils/PearlTrace.java")
                                .contains(".fabrolia$hasLoadedPearlInChunk(packedChunkPos)"),
                "a clean weak section scan miss needs the hidden-inclusive index fallback");
        int residentWalk = manager.indexOf("fabrolia$forEachResidentPearl(");
        int residentProof = manager.indexOf(
                "fabrolia$loadedPearl(candidate.getUUID()) == candidate", residentWalk);
        int residentAccept = manager.indexOf("action.accept(candidate)", residentProof);
        assertTrue(residentWalk >= 0 && residentProof > residentWalk
                        && residentAccept > residentProof,
                "logout must recover a published-before-first-tick pearl from the strict "
                        + "visible/HIDDEN resident index, never from a leaked C2ME entry");
        int holdFallback = manager.indexOf("fabrolia$hasLoadedPearlInChunk");
        int holdFallbackEnd = manager.indexOf("fabrolia$indexLoadedPearl", holdFallback);
        assertFalse(manager.substring(holdFallback, holdFallbackEnd)
                        .contains("fabrolia$isResidentPearl"),
                "a busy onMove lookup must conservatively hold, not recreate a clean false miss");
        assertTrue(manager.contains("this.fabrolia$tickingChunks =")
                        && manager.contains("this.fabrolia$loadedPearls =")
                        && manager.indexOf("this.fabrolia$tickingChunks =")
                        < manager.indexOf("Long2ObjectConcurrentHashMap<Visibility> concurrentVisibility"),
                "target-owned mixin fields require explicit constructor initialization");
    }


    @Test
    void ticketHoldIncludesHiddenSectionsAndDoesNotInventAResultOnProbeFailure() throws Exception {
        String text = source("common/utils/PearlTrace.java");
        int has = text.indexOf("public static boolean hasLivePearlInChunk");
        int listed = text.indexOf("public static String livePearlsInChunk", has);
        assertFalse(text.substring(has, listed).contains("isAccessible()"),
                "HIDDEN is the exact C2ME window the hold must rescue");
        assertFalse(text.substring(has, listed).contains("catch (RuntimeException failure)"),
                "a failed live-ticket probe must propagate instead of inventing a hold result");
        int listedEnd = text.indexOf("public static void holdEngaged", listed);
        assertFalse(text.substring(listed, listedEnd).contains("isAccessible()"));
    }


    @Test
    void ownerFalseDeathRepairRequiresThePublishedDimensionTransition() throws Exception {
        String player = source("common/mixin/server/ServerPlayerMixin.java");
        int enter = player.indexOf("PlayerDimensionTransition.enter(self)");
        int call = player.indexOf("original.call(transition)", enter);
        int exit = player.indexOf("PlayerDimensionTransition.exit(self)", call);
        assertTrue(enter >= 0 && call > enter && exit > call
                        && player.substring(call, exit).contains("finally"),
                "the marker must bracket the full cross-dimension teleport even on failure");

        String pearl = source("common/mixin/entity/ThrownEnderpearlMixin.java");
        assertTrue(pearl.contains("target = \"Lnet/minecraft/world/entity/Entity;isAlive()Z\"")
                        && pearl.contains("PlayerDimensionTransition")
                        && pearl.contains(".contains(player)")
                        && pearl.contains("player.isDeadOrDying()"),
                "only a worker-observed false value inside the exact live transition may be repaired");

        String marker = source("common/parallelised/utils/PlayerDimensionTransition.java");
        assertTrue(marker.contains("IdentityHashMap<ServerPlayer, Integer>")
                        && marker.contains("ACTIVE_DEPTH.merge(player, 1")
                        && marker.contains("ACTIVE_DEPTH.put(player, depth - 1)"),
                "a nested transition must not let the inner finally clear the outer marker");

        String list = source("common/mixin/server/PlayerListMixin.java");
        int putHook = list.indexOf("fabrolia$publishPlayerSessionSuccessor");
        int put = list.indexOf("original.call(sessions, key, value)", putHook);
        int publish = list.indexOf("fabrolia$publishPearlSessionSuccessor(successor", put);
        int existingRegistry = list.indexOf("displaced.getEnderPearls()", publish);
        int immediateCopy = list.indexOf("successor.registerEnderPearl(pearl)", existingRegistry);
        assertTrue(list.contains("placeNewPlayer(Lnet/minecraft/network/Connection;")
                        && !list.substring(putHook, put).contains("respawn(")
                        && putHook >= 0 && put > putHook && publish > put
                        && existingRegistry > publish && immediateCopy > existingRegistry,
                "session replacement must publish old-to-new after the unique UUID-map put, "
                        + "then immediately copy the live pearl registry without masking "
                        + "death-respawn semantics");
        assertTrue(player.contains("volatile ServerPlayer fabrolia$pearlSessionSuccessor")
                        && player.contains("implements\n"
                                + "        com.axalotl.fabrolia.common.parallelised.utils."
                                + "PearlOwnerSessionAccess"),
                "a worker already holding the old owner needs a JMM-visible successor");
        int successorRead = pearl.indexOf(".fabrolia$pearlSessionSuccessor()");
        int livePublication = pearl.indexOf("fabrolia$pearlSessionReplacementWasLive()", successorRead);
        int replacementAlive = pearl.indexOf("successor.isAlive()", livePublication);
        int successorDeathFence = pearl.indexOf("!successor.isDeadOrDying()", replacementAlive);
        assertTrue(successorRead >= 0 && livePublication > successorRead
                        && replacementAlive > livePublication
                        && successorDeathFence > replacementAlive,
                "only a main-thread live handoff with a live published successor may bypass false death");
    }

    @Test
    void staleDeferredPearlTeleportIsDroppedUnderTheMainFence() throws Exception {
        String pearl = source("common/mixin/entity/ThrownEnderpearlMixin.java");
        int route = pearl.indexOf("fabrolia$routePlayerTeleportToMain");
        int fence = pearl.indexOf("AsyncIslandPlayerTicks.runFencedMainMutation(", route);
        int recheck = pearl.indexOf("!fabrolia$ownerTeleportAllowed(player, level)", fence);
        int drop = pearl.indexOf("return;", recheck);
        int endermite = pearl.indexOf("level.addFreshEntity(endermite)", drop);
        int teleport = pearl.indexOf("player.teleport(new TeleportTransition(", endermite);
        int enqueue = pearl.indexOf("RegionScheduler.enqueueGlobalMainOnly(mainContinuation)", teleport);
        assertTrue(route >= 0 && fence > route && recheck > fence && drop > recheck
                        && endermite > drop && teleport > endermite && enqueue > teleport,
                "the queued pearl landing must re-check its owner under the all-island fence, "
                        + "drop a stale landing, and keep the endermite spawn and teleport inside it");
        assertFalse(pearl.substring(route).contains("throw new IllegalStateException"),
                "a lifecycle change before the queued landing is vanilla 'no teleport', not a fail-stop");
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            Path direct = root.resolve("src/main/java/com/axalotl/fabrolia").resolve(relative);
            Path viaCommon = root.resolve("common/src/main/java/com/axalotl/fabrolia").resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(viaCommon)) return Files.readString(viaCommon);
        }
        Assumptions.assumeTrue(false, "source tree not available");
        throw new AssertionError("unreachable");
    }

    private static int occurrences(String text, String needle) {
        int matches = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0;
                at += needle.length()) {
            matches++;
        }
        return matches;
    }

    private static String resource(String relative) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++, root = root.getParent()) {
            Path direct = root.resolve("src/main/resources").resolve(relative);
            Path viaCommon = root.resolve("common/src/main/resources").resolve(relative);
            if (Files.isRegularFile(direct)) return Files.readString(direct);
            if (Files.isRegularFile(viaCommon)) return Files.readString(viaCommon);
        }
        Assumptions.assumeTrue(false, "resource tree not available");
        throw new AssertionError("unreachable");
    }
}
