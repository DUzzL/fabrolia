package com.axalotl.fabrolia.common.parallelised.advancement;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Set;

/**
 * Per-key fast paths for {@link net.minecraft.advancements.predicates.NbtPredicate#matches(Entity)}
 * that avoid the full {@code Entity.saveWithoutId} + Mojang Codec encode chain when the
 * predicate's expected tag references only fields we can read directly from the entity.
 *
 * <p>Vanilla's path: {@code matches(Entity) → getEntityTagToCompare(entity) → entity.saveWithoutId(new CompoundTag())},
 * which on a {@code ServerPlayer} encodes inventory + ender chest + abilities + game-type + stats
 * + advancements + bukkit/forge extra data + … via a deep Codec tree. Profiled at ~0.7% of server
 * thread on a 23-player session, all to compare a one-key predicate like
 * {@code {TicksFrozen:140}} or {@code {ShoulderEntityLeft:{id:"minecraft:parrot"}}}.
 *
 * <p>The pack of advancements responsible (BACAP):
 * <ul>
 *   <li>{@code blazeandcave:nether/polar_opposites} — trigger {@code enter_block},
 *       predicate {@code {TicksFrozen:140}}</li>
 *   <li>{@code blazeandcave:technical/riddle_ninth_line} (×4 criteria) — trigger
 *       {@code using_item}, predicate {@code {ShoulderEntityLeft/Right:{id:"minecraft:parrot"}}}</li>
 *   <li>{@code blazeandcave:weaponry/explosive_fire} — trigger {@code inventory_changed},
 *       predicate {@code {Inventory:[{id:"minecraft:crossbow",components:{…}}]}} — not handled
 *       by this fast path (the inventory walk is a separate optimisation lane)</li>
 * </ul>
 *
 * <p>Semantics: identical to vanilla — the fast path materialises only the keys present in the
 * predicate's tag and hands them to {@link NbtUtils#compareNbt} (subset match, same as vanilla).
 * If any predicate key is outside the handled set, returns {@code null} and the caller falls
 * back to the vanilla full-serialise path.
 */
public final class NbtPredicateFastPath {

    private NbtPredicateFastPath() {}

    /**
     * @return {@code Boolean.TRUE} / {@code FALSE} if every key in {@code expected} is handled
     *         by a direct accessor; {@code null} if any key is unsupported and the caller must
     *         fall through to the vanilla path.
     */
    public static Boolean tryMatch(Entity entity, CompoundTag expected) {
        if (expected == null || expected.isEmpty()) return Boolean.TRUE;

        Set<String> keys = expected.keySet();
        // Construct only the slice of the entity tag the predicate cares about. compareNbt
        // does the actual subset match — feeding it a tag with extra unrelated keys would
        // still work, but materialising only the required slice keeps the build cheap.
        CompoundTag minimal = new CompoundTag();

        for (String key : keys) {
            if (!extractKey(entity, key, minimal)) {
                return null;
            }
        }

        return NbtUtils.compareNbt(expected, minimal, true);
    }

    /**
     * @return {@code true} if {@code key} was handled (entry written to {@code out} when
     *         present on the entity, or intentionally omitted when absent — both are correct
     *         inputs for the downstream {@link NbtUtils#compareNbt} subset match);
     *         {@code false} if the key is unsupported.
     */
    private static boolean extractKey(Entity entity, String key, CompoundTag out) {
        switch (key) {
            case "TicksFrozen": {
                // Public on Entity since 1.17; same int the freeze tick logic reads/writes.
                // saveWithoutId only emits the key when value > 0; mirror that so the
                // subset comparator behaves identically (a predicate of {TicksFrozen:0} would
                // otherwise spuriously match an unfrozen entity here but not in vanilla).
                int ticks = entity.getTicksFrozen();
                if (ticks > 0) out.putInt("TicksFrozen", ticks);
                return true;
            }
            case "ShoulderEntityLeft":
            case "ShoulderEntityRight": {
                if (!(entity instanceof Player player)) return false;
                boolean present = key.equals("ShoulderEntityLeft")
                        ? player.getShoulderParrotLeft().isPresent()
                        : player.getShoulderParrotRight().isPresent();
                if (!present) {
                    // Match vanilla's addAdditionalSaveData: when absent, the key isn't written.
                    return true;
                }
                // Modern (1.21.6+) shoulder slot only holds parrots — the Player field is
                // typed Optional<Parrot.Variant>. saveWithoutId emits the legacy entity-tag
                // shape including {id:"minecraft:parrot", ...variant data}. Every BAC predicate
                // here only checks the {id} subset, so writing the id is sufficient for the
                // compareNbt to match; if a future predicate inspects variant data, this fast
                // path will produce a false negative — which the caller has no way to detect.
                // Guard: only handle this key when the predicate's nested compound asks for
                // strictly the id (no other keys), so we never silently miss a match.
                CompoundTag perKey = new CompoundTag();
                perKey.putString("id", "minecraft:parrot");
                out.put(key, perKey);
                return true;
            }
            default:
                return false;
        }
    }

    /**
     * Pre-check the predicate shape before constructing anything — used by the mixin to gate
     * fast-path eligibility without allocating. Returns {@code false} if any key would force a
     * fallback OR if a nested compound asks for more than this fast path knows how to satisfy
     * (e.g. {@code ShoulderEntityLeft.variant} — we'd need to decode the Parrot.Variant codec
     * which is exactly the cost we're trying to avoid).
     */
    public static boolean isHandled(CompoundTag expected) {
        if (expected == null || expected.isEmpty()) return true;
        for (String key : expected.keySet()) {
            switch (key) {
                case "TicksFrozen":
                    if (!(expected.get(key) instanceof net.minecraft.nbt.NumericTag)) return false;
                    break;
                case "ShoulderEntityLeft":
                case "ShoulderEntityRight": {
                    // Allow only {id:"…"} nested shape — see comment above on variant.
                    CompoundTag inner = expected.getCompoundOrEmpty(key);
                    if (inner.isEmpty()) return false;
                    for (String innerKey : inner.keySet()) {
                        if (!"id".equals(innerKey)) return false;
                    }
                    break;
                }
                default:
                    return false;
            }
        }
        return true;
    }
}
