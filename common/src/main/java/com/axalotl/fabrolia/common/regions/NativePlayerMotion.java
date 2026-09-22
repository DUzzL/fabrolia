package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.entity.EntityMovementAccessor;
import com.axalotl.fabrolia.common.mixin.entity.PlayerMotionGameModeAccessor;
import com.axalotl.fabrolia.common.mixin.entity.PlayerMotionLivingAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.AABB;

/**
 * Certificate for the ordinary dry, supported native player tick, not a physics
 * predictor. Failure selects the original unentered body on Main. All world reads
 * follow live source membership and actual core-column ownership checks; the
 * RegionKeyed hint and the frontier halo alone never certify a motion footprint.
 */
final class NativePlayerMotion {
    private NativePlayerMotion() {}

    private static final ThreadLocal<BlockPos.MutableBlockPos> POSITION =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private static final ClassValue<Boolean> ITEMS = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            return nativeClass(type) && inherits(type, Item.class, "inventoryTick");
        }
    };
    private static final ClassValue<Boolean> BLOCKS = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            if (!nativeClass(type)) return false;
            // Air's only shape override is the native empty shape.
            if (type == AirBlock.class) return true;
            return inherits(type, BlockBehaviour.class, "entityInside", "getCollisionShape",
                    "getEntityInsideCollisionShape", "getShape", "getFluidState",
                    "isCollisionShapeFullBlock")
                    && inherits(type, Block.class, "stepOn", "fallOn", "getFriction",
                    "getSpeedFactor", "getJumpFactor", "getBounceRestitution");
        }
    };

    /** Check implementation provenance without executing an unknown callback. */
    private static boolean nativeClass(Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        var nativeSource = Entity.class.getProtectionDomain().getCodeSource();
        return source != null && nativeSource != null && source.equals(nativeSource)
                && type.getClassLoader() == Entity.class.getClassLoader();
    }

    private static boolean inherits(Class<?> type, Class<?> owner, String... names) {
        for (Class<?> cursor = type; cursor != owner; cursor = cursor.getSuperclass()) {
            if (cursor == null) return false;
            for (var method : cursor.getDeclaredMethods()) {
                for (String name : names) if (method.getName().equals(name)) return false;
            }
        }
        return true;
    }

    static boolean certifies(RegionizedServer runtime, IslandRegistry.PIsland island,
                             ServerPlayer player) {
        if (runtime == null || island == null || island.retired || island.domain == null
                || !RegionScheduler.ownsRuntimeGeneration(runtime)
                || !RegionScheduler.ownsExclusiveGameplay()
                && (!island.unitClaim.get() || island.claimHolder != Thread.currentThread())) {
            return false;
        }
        // Identity membership precedes every mutable native field below.
        if (!(player instanceof RegionKeyed keyed)) return false;
        Region source = runtime.regionizer().peekByGridKey(keyed.fabrolia$getRegionKey());
        if (source == null || source.domain != island.domain || !runtime.ownsRegion(source)
                || ((java.util.NavigableSet<Entity>) source.entities).ceiling(player) != player
                || !island.cells.contains(source.gridKey)) return false;
        if (AsyncIslandPlayerTicks.classifyPlayer(player) == null
                || player.level() != runtime.level || player.isRemoved()
                || player.isDeadOrDying() || player.isPassenger() || player.isVehicle()
                || player.isSleeping() || player.isUsingItem() || !player.getUseItem().isEmpty()
                || !player.getActiveEffects().isEmpty() || player.portalProcess != null
                || player.containerMenu != player.inventoryMenu
                || player.gameMode.getClass() != net.minecraft.server.level.ServerPlayerGameMode.class
                || ((PlayerMotionGameModeAccessor) player.gameMode).fabrolia$motionDestroyingBlock()
                || ((PlayerMotionGameModeAccessor) player.gameMode).fabrolia$motionDelayedDestroy()
                || player.getPose() != Pose.STANDING || player.noPhysics || player.isSpectator()
                || player.getAbilities().flying || player.isFallFlying() || player.isAutoSpinAttack()
                || player.isSwimming() || player.isInWater() || player.isInLava()
                || player.isInPowderSnow || player.wasInPowderSnow
                || !player.onGround() || player.fallDistance != 0.0
                || !player.getLastClientInput().equals(Input.EMPTY)
                || player.xxa != 0.0F || player.yya != 0.0F || player.zza != 0.0F
                || !Float.isFinite(player.getSpeed())
                || player.getInterpolation().hasActiveInterpolation()
                || !PlayerTickKnownModEnvelope.idle(player)) return false;
        var living = (PlayerMotionLivingAccessor) player;
        var movement = (EntityMovementAccessor) player;
        var velocity = player.getDeltaMovement();
        // Native idle gravity leaves negative Y: support, not a speed constant,
        // proves that this downward request cannot move the player horizontally.
        if (living.fabrolia$motionJumping() || velocity.x != 0.0 || velocity.z != 0.0
                || !Double.isFinite(velocity.y) || velocity.y > 0.0
                || !movement.fabrolia$stuckSpeedMultiplier().equals(net.minecraft.world.phys.Vec3.ZERO)
                || !movement.fabrolia$movementThisTick().isEmpty()
                || player.xOld != player.getX() || player.yOld != player.getY()
                || player.zOld != player.getZ()) return false;
        for (var effects : living.fabrolia$motionLocationEnchantments().values()) {
            if (!(effects instanceof java.util.Map<?, ?> map) || !map.isEmpty()) return false;
        }
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!ItemStack.matches(stack, living.fabrolia$motionLastEquipment().get(slot))
                    || !safeItem(stack)) return false;
        }
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!safeItem(inventory.getItem(i))) return false;
        }
        AABB box = player.getBoundingBox();
        // Both the native collision iterator's neighbouring blocks and refreshed
        // fluid volume must be owned before sampling any of their states.
        AABB fluid = player.getFluidInteractionBox();
        double minX = Math.min(box.minX, fluid.minX) - 1.0;
        double maxX = Math.max(box.maxX, fluid.maxX) + 1.0;
        double minZ = Math.min(box.minZ, fluid.minZ) - 1.0;
        double maxZ = Math.max(box.maxZ, fluid.maxZ) + 1.0;
        double minY = Math.min(box.minY + velocity.y, fluid.minY) - 1.0;
        double maxY = Math.max(box.maxY, fluid.maxY) + 1.0;
        if (!(minX > Integer.MIN_VALUE && maxX < Integer.MAX_VALUE
                && minZ > Integer.MIN_VALUE && maxZ < Integer.MAX_VALUE
                && minY >= runtime.level.getMinY() && maxY <= runtime.level.getMaxY()
                && box.minY == Math.floor(box.minY))) return false;
        int x0 = (int) Math.floor(minX), x1 = (int) Math.floor(maxX);
        int z0 = (int) Math.floor(minZ), z1 = (int) Math.floor(maxZ);
        for (int z = z0 >> 4; z <= z1 >> 4; z++) {
            for (int x = x0 >> 4; x <= x1 >> 4; x++) {
                long chunk = ChunkPos.pack(x, z);
                Region region = runtime.regionizer().peekRegionForChunk(chunk);
                if (region == null || region.domain != island.domain
                        || !island.cells.contains(region.gridKey) || !runtime.ownsRegion(region)
                        || runtime.islandOwnerOfChunk(chunk) != island
                        || runtime.level.getChunkSource().getChunkNow(x, z) == null) return false;
            }
        }
        BlockPos.MutableBlockPos pos = POSITION.get();
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                var chunk = runtime.level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) return false;
                for (int y = (int) Math.floor(minY); y <= (int) Math.floor(maxY); y++) {
                    pos.set(x, y, z);
                    var state = chunk.getBlockState(pos);
                    if (!BLOCKS.get(state.getBlock().getClass()) || !state.getFluidState().isEmpty()
                            || state.hasOffsetFunction()
                            || state.is(net.minecraft.tags.BlockTags.CLIMBABLE)) {
                        return false;
                    }
                }
            }
        }
        // Full, ordinary support under every part of the current box: no stale
        // onGround/support-position assumption and no speculative collision run.
        int supportY = (int) box.minY - 1;
        for (int z = (int) Math.floor(box.minZ); z < Math.ceil(box.maxZ); z++) {
            for (int x = (int) Math.floor(box.minX); x < Math.ceil(box.maxX); x++) {
                pos.set(x, supportY, z);
                var state = runtime.level.getChunkSource().getChunkNow(x >> 4, z >> 4).getBlockState(pos);
                float friction = state.getBlock().getFriction();
                if (!(friction > 0.0F) || !Float.isFinite(friction)
                        || !state.isCollisionShapeFullBlock(runtime.level, pos)) return false;
            }
        }
        return true;
    }

    static boolean safeItem(ItemStack stack) {
        // Death protection may execute arbitrary native consume effects (including
        // teleportation) during pre-travel fire/starvation damage without item use.
        return stack.isEmpty() || !stack.isEnchanted()
                && !stack.has(net.minecraft.core.component.DataComponents.DEATH_PROTECTION)
                && ITEMS.get(stack.getItem().getClass());
    }
}
