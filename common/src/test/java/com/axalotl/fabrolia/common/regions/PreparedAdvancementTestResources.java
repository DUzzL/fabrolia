package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.common.parallelised.advancement.AdvancementRequirements;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.ServerAdvancementManager;

import java.lang.reflect.Field;
import java.util.List;

/** Supplies the real prepared empty policy where Minecraft construction is bypassed. */
final class PreparedAdvancementTestResources {
    private PreparedAdvancementTestResources() {}

    static ReloadableServerResources install(MinecraftServer server) throws Exception {
        return install(server, List.of());
    }

    static ReloadableServerResources install(
            MinecraftServer server, List<AdvancementHolder> holders) throws Exception {
        var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        var advancements = (PreparedAdvancements) unsafe.allocateInstance(PreparedAdvancements.class);
        advancements.requirements = AdvancementRequirements.prepare(
                holders.stream().map(AdvancementHolder::value).toList(), RegistryAccess.EMPTY);
        var managers = (ReloadableServerResources) unsafe.allocateInstance(ReloadableServerResources.class);
        field(ReloadableServerResources.class, "advancements").set(managers, advancements);
        Field resources = field(MinecraftServer.class, "resources");
        var constructor = resources.getType().getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        resources.set(server, constructor.newInstance(null, managers));
        return managers;
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class PreparedAdvancements extends ServerAdvancementManager
            implements AdvancementRequirements.Provider {
        AdvancementRequirements requirements;
        private PreparedAdvancements() { super(null); }
        @Override public AdvancementRequirements fabrolia$advancementRequirements() { return requirements; }
    }
}
