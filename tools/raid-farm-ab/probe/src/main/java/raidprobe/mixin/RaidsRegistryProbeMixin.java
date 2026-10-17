package raidprobe.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import raidprobe.RaidProbe;

import java.util.Arrays;
import java.util.List;

/** Check codec insertion order against an untouched native fastutil table. */
@Mixin(Raids.class)
public class RaidsRegistryProbeMixin {
    @Shadow @Final private Int2ObjectMap<Raid> raidMap;

    @Inject(method = "<init>(Ljava/util/List;II)V", at = @At("RETURN"))
    private void probe$decoded(List<?> saved, int nextId, int tick, CallbackInfo ci) {
        Int2ObjectMap<Object> expected = RaidProbe.nativeIntMap();
        for (Object entry : saved) {
            try {
                // RaidWithId is a private record; only inspect the codec's
                // constructor input, never query or mutate a live raid body.
                var id = entry.getClass().getDeclaredMethod("id");
                id.setAccessible(true);
                expected.put((int) id.invoke(entry), entry);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot inspect saved raid registration", failure);
            }
        }
        int[] actualOrder = raidMap.keySet().toIntArray();
        int[] expectedOrder = expected.keySet().toIntArray();
        RaidProbe.log("REGISTRY_DECODE class=" + raidMap.getClass().getSimpleName()
                + " table=" + RaidProbe.registryTable(raidMap) + " reference=" + expected.getClass().getSimpleName()
                + " entries=" + saved.size() + " orderMatches=" + Arrays.equals(actualOrder, expectedOrder)
                + " actual=" + Arrays.toString(actualOrder) + " expected=" + Arrays.toString(expectedOrder));
    }
}
