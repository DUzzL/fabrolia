package com.axalotl.fabrolia.common.platform;

import net.minecraft.commands.CommandSourceStack;

import java.util.ServiceLoader;

public class PlatformUtils {
    /** Loaded on first use, so the platform hooks below stay usable before mod init. */
    private static final class ModHolder {
        static final ModPlatform MOD_PLATFORM = PlatformUtils.load(ModPlatform.class);
    }
    private static MinecraftPlatform minecraftPlatform;

    public static void initialize() {
        PlatformUtils.minecraftPlatform = PlatformUtils.load(MinecraftPlatform.class);
    }

    public static void saveConfig() {
        ModHolder.MOD_PLATFORM.saveConfig();
    }

    public static void reloadConfig() {
        ModHolder.MOD_PLATFORM.reloadConfig();
    }

    public static boolean isModLoaded(String modId) {
        return ModHolder.MOD_PLATFORM.isModLoaded(modId);
    }

    public static boolean hasPermission(CommandSourceStack source, String node, int level) {
        return minecraftPlatform.hasPermission(source, node, level);
    }

    public static PearlLanding onEnderPearlLand(net.minecraft.server.level.ServerPlayer player,
                                                net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl pearl,
                                                net.minecraft.world.phys.Vec3 hitPosition,
                                                net.minecraft.world.phys.HitResult hitResult,
                                                net.minecraft.world.phys.Vec3 previousPosition) {
        return minecraftPlatform.onEnderPearlLand(player, pearl, hitPosition, hitResult, previousPosition);
    }


    /** Null-safe before {@link #initialize}: vanilla packet. */
    public static net.minecraft.network.protocol.Packet<?> chunkPacket(
            net.minecraft.world.level.chunk.LevelChunk chunk,
            net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet) {
        MinecraftPlatform platform = minecraftPlatform;
        return platform == null ? packet : platform.chunkPacket(chunk, packet);
    }

    public static boolean firesChunkSent() {
        MinecraftPlatform platform = minecraftPlatform;
        return platform != null && platform.firesChunkSent();
    }

    public static void chunkSent(net.minecraft.server.level.ServerPlayer player,
                                 net.minecraft.world.level.chunk.LevelChunk chunk,
                                 net.minecraft.server.level.ServerLevel level) {
        minecraftPlatform.chunkSent(player, chunk, level);
    }

    private static <T> T load(Class<T> clazz) {
        return ServiceLoader.load(clazz, clazz.getClassLoader()).findFirst().orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
    }
}