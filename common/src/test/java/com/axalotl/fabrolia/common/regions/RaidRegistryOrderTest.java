package com.axalotl.fabrolia.common.regions;

import com.axalotl.fabrolia.api.fastutil.Int2ObjectConcurrentHashMap;
import com.axalotl.fabrolia.common.mixin.world.RaidsOmenRouteMixin;
import com.axalotl.fabrolia.common.utils.CopyOnWriteInt2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Equal-distance stacked raids make the registry's table order observable to villager AI. */
class RaidRegistryOrderTest {
    private static final BlockPos CENTER = new BlockPos(-8, 184, 8);

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void publishedLookupSelectsVanillasFirstTieRatherThanTheStripedMapsTie() throws Exception {
        Int2ObjectMap<Raid> vanillaMap = new Int2ObjectOpenHashMap<>();
        CopyOnWriteInt2ObjectMap<Raid> publishedMap = new CopyOnWriteInt2ObjectMap<>(16);
        Int2ObjectMap<Raid> oldMap = new Int2ObjectConcurrentHashMap<>();
        Raids vanilla = new Raids();
        Raids old = new Raids();
        var field = Raids.class.getDeclaredField("raidMap");
        field.setAccessible(true);
        field.set(vanilla, vanillaMap);
        field.set(old, oldMap);
        RaidsOmenRouteMixin published = new RaidsOmenRouteMixin() {};
        var publishedField = RaidsOmenRouteMixin.class.getDeclaredField("raidMap");
        publishedField.setAccessible(true);
        publishedField.set(published, publishedMap);
        var lookup = RaidsOmenRouteMixin.class.getDeclaredMethod("fabrolia$nearby", BlockPos.class, int.class);
        lookup.setAccessible(true);
        boolean reproducedWrongTie = false;
        for (int id = 2531; id < 2660; id++) {
            Raid raid = new PublishedRaid();
            vanillaMap.put(id, raid);
            publishedMap.put(id, raid);
            oldMap.put(id, raid);
            Raid expected = vanilla.getNearbyRaid(CENTER, 9216);
            assertNotNull(expected);
            assertSame(expected, lookup.invoke(published, CENTER, 9216), "raid id " + id);
            reproducedWrongTie |= expected != old.getNearbyRaid(CENTER, 9216);
        }
        assertTrue(reproducedWrongTie, "this fixture must reproduce the former striped-map selection bug");
        var nativeIterator = vanillaMap.values().iterator();
        var publishedIterator = published.fabrolia$raidIterator();
        while (nativeIterator.hasNext()) {
            assertSame(nativeIterator.next(), publishedIterator.next());
            nativeIterator.remove();
            publishedIterator.remove();
            assertSame(vanilla.getNearbyRaid(CENTER, 9216), lookup.invoke(published, CENTER, 9216));
        }
    }

    private static final class PublishedRaid extends Raid implements RaidOwnerAccess {
        PublishedRaid() { super(CENTER, Difficulty.HARD); }

        @Override public View fabrolia$raidView() {
            return new View(getCenter(), isActive(), isStopped());
        }

        @Override public boolean fabrolia$absorbOmenIfEligible(ServerPlayer player, MobEffectInstance effect) {
            throw new UnsupportedOperationException("lookup fixture");
        }
    }
}
