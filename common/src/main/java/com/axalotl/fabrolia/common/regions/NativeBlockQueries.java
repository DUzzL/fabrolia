package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.mixin.entity.NativeEntityDependencyAccessors;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.Fluids;

/** Callback schemas for queries only; these do not approve entityInside or motion effects. */
final class NativeBlockQueries {
    private NativeBlockQueries() {}

    private record Program(boolean collision, boolean shape, boolean interaction, boolean path,
                           boolean liquid, boolean stateOnlyGeometry) {}
    private static final Program UNKNOWN = new Program(false, false, false, false, false, false);
    private static final ClassValue<Program> PROGRAMS = new ClassValue<>() {
        @Override protected Program computeValue(Class<?> type) {
            if (!nativeClass(type)) return UNKNOWN;
            Class<?> path = declaration(type, "isPathfindable");
            // These exact native implementations read only state properties and
            // fixed shapes or constructor-built private shape tables. Neither
            // world/context callbacks nor neighbouring states are consulted.
            // Their event, placement and entityInside programs are not admitted.
            boolean stateOnlyGeometry = type == AmethystClusterBlock.class || type == FenceBlock.class
                    || type == SnowLayerBlock.class || type == ChestBlock.class
                    || type == SculkSensorBlock.class || type == CactusBlock.class
                    || type == CarpetBlock.class || type == StairBlock.class;
            return new Program(declaration(type, "getCollisionShape") == BlockBehaviour.class,
                    declaration(type, "getShape") == BlockBehaviour.class,
                    declaration(type, "getInteractionShape") == BlockBehaviour.class,
                    (stateOnlyGeometry || path == BlockBehaviour.class || path == VegetationBlock.class || type == LiquidBlock.class)
                            && declaration(type, "isCollisionShapeFullBlock") == BlockBehaviour.class,
                    type == LiquidBlock.class, stateOnlyGeometry);
        }
    };
    private static final ClassValue<Boolean> LIQUID_OBSERVER = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            return nativeClass(type)
                    && declaration(type, "getLiquidCollisionShape") == LivingEntity.class
                    && declaration(type, "canStandOnFluid") == LivingEntity.class;
        }
    };

    private static boolean nativeClass(Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        return source != null && source.equals(Entity.class.getProtectionDomain().getCodeSource())
                && type.getClassLoader() == Entity.class.getClassLoader();
    }

    private static Class<?> declaration(Class<?> type, String name) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            for (var method : cursor.getDeclaredMethods()) {
                if (method.getName().equals(name)) return cursor;
            }
        }
        return null;
    }

    static boolean collision(Mob source, BlockState state) {
        if (state.getClass() != BlockState.class) return false;
        Block block = state.getBlock();
        Program program = PROGRAMS.get(block.getClass());
        if (program.stateOnlyGeometry) return program.interaction;
        if (program.collision) {
            // Native inherited getCollisionShape returns EMPTY immediately here;
            // an overridden plant getShape/offset callback is never invoked.
            if (!((NativeEntityDependencyAccessors.BlockCollision) block).fabrolia$hasCollision()) return true;
            return program.shape && program.interaction;
        }
        // Liquid collision observes only this already-owned source and the
        // above fluid state. The contextual overload never uses the state cache.
        return program.liquid && (block == Blocks.WATER || block == Blocks.LAVA)
                && program.interaction && LIQUID_OBSERVER.get(source.getClass())
                && state.getFluidState() != null
                && state.getFluidState().getClass() == net.minecraft.world.level.material.FluidState.class;
    }

    static boolean navigation(Mob source, BlockState state) {
        if (!collision(source, state) || !PROGRAMS.get(state.getBlock().getClass()).path) return false;
        var fluidState = state.getFluidState();
        if (fluidState.getClass() != net.minecraft.world.level.material.FluidState.class) return false;
        var fluid = fluidState.getType();
        return fluid == Fluids.EMPTY || fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER
                || fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA;
    }

    /** The caller owns each FULL column before this reusable palette inspection. */
    static final class Columns implements Predicate<BlockState> {
        private final LongOpenHashSet checked = new LongOpenHashSet();
        private Mob source;
        private Mob collisionSource;
        private final Predicate<BlockState> unsafeCollision = state -> !collision(collisionSource, state);

        void reset(Mob source) {
            checked.clear();
            this.source = source;
        }

        boolean contains(Mob observer, LevelChunk chunk) {
            return source == observer && chunk != null && chunk.getClass() == LevelChunk.class
                    && checked.contains(chunk.getPos().pack());
        }

        boolean accepts(LevelChunk chunk) {
            if (source == null || chunk == null || chunk.getClass() != LevelChunk.class
                    || chunk.getLevel().isDebug()) return false;
            long key = chunk.getPos().pack();
            if (checked.contains(key)) return true;
            for (LevelChunkSection section : chunk.getSections()) {
                if (section == null || section.getClass() != LevelChunkSection.class
                        || section.getStates().getClass() != PalettedContainer.class
                        || section.getStates().maybeHas(this)) return false;
            }
            checked.add(key);
            return true;
        }

        boolean acceptsCollisionSection(Mob observer, LevelChunkSection section) {
            if (observer == null || section == null || section.getClass() != LevelChunkSection.class
                    || section.getStates().getClass() != PalettedContainer.class) return false;
            collisionSource = observer;
            try {
                return !section.getStates().maybeHas(unsafeCollision);
            } finally {
                collisionSource = null;
            }
        }

        @Override public boolean test(BlockState state) {
            return !navigation(source, state);
        }
    }
}
