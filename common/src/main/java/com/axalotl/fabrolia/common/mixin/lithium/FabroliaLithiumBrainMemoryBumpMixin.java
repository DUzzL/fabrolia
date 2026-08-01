package com.axalotl.fabrolia.common.mixin.lithium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.caffeinemc.mods.lithium.common.ai.brain.memories.MemoryModificationCounter;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Fix B of the villager-freeze repair — the root cause. Fix A is
 * {@link FabroliaLithiumBrainMemoryCounterMixin}.
 *
 * <h2>The defect</h2>
 * Lithium's {@code ai.task.memory_changes} module caches the result of
 * {@code Behavior.hasRequiredMemories(E)} against a per-brain memory-modification
 * counter, and {@code Behavior.tryStart} is
 * {@code hasRequiredMemories(body) && checkExtraStartConditions(...)}. The counter
 * is bumped BEFORE the mutation it announces:
 *
 * <pre>{@code
 * // lithium ai/task/memory_changes/BrainMixin: clearTrackingChanges, both setTrackingChanges
 * if (instance.hasValue()) { this.lithium$onMemoryModified(); }   // bump
 * original.call(instance);                                       // THEN mutate
 * }</pre>
 *
 * A reader that lands in that window reads the post-bump count {@code K+1},
 * recomputes {@code hasRequiredMemories} from PRE-mutation memory state, and caches
 * {@code (K+1, staleResult)}. The mutation then completes and the counter stays at
 * {@code K+1} — so the behavior's start gate is latched on a value that never
 * changes again until some UNRELATED memory write happens to bump the counter.
 * A latched {@code false} means the behavior never starts, walk targets are never
 * set, and the villager stands still while sensors and {@code AcquirePoi} keep
 * running normally (the memories were written; only the cached gate is stale).
 *
 * <p>The framing that matters: vanilla {@code hasRequiredMemories} re-reads
 * {@code brain.checkMemory(...)} on every call, so the same interleaving self-heals
 * on the next tick. <b>Lithium converts a transient race into a permanent latch.</b>
 * No amount of {@code volatile} fixes it — Fix A alone is not enough.
 *
 * <p>The window is reachable under Fabrolia because brains are written CROSS-ENTITY
 * ({@code BehaviorUtils.setWalkAndLookTargetMemories(otherEntity, …)},
 * {@code LivingEntity.setLastHurtByMob} into the target's brain, raid / golem-spawn /
 * trading paths that partly run on {@code Server thread}) and there is no barrier
 * between workers WITHIN a tick. Villagers are in neither opt-out set —
 * not {@code FabroliaConfig.synchronizedEntities}, not
 * {@code ParallelProcessor.BLOCKED_BASE_CLASSES} — so their brains tick on region
 * workers.
 *
 * <h2>The repair</h2>
 * Guarantee a SECOND bump that no reader can have observed before the mutation
 * completed. Any cache entry computed from stale state is then invalidated by the
 * trailing bump, and the gate re-arms on the next call. Combined with Fix A the
 * trailing bump is a release and the cache read is an acquire.
 *
 * <p>This deliberately does not touch lithium's {@code @WrapOperation} handlers —
 * wrapping a handler whose signature already carries an {@code Operation} is
 * fragile, and stacking a second {@code @WrapOperation} on the same
 * {@code MemorySlot.clear()/set()} instruction would make correctness depend on
 * injector nesting order. Wrapping the whole vanilla mutator instead is
 * order-independent: lithium (priority 1000) binds its {@code @WrapOperation}s
 * inside {@code setMemoryInternal} before this mixin (priority 1600) moves the body.
 *
 * <h2>Why the bump is CONDITIONAL</h2>
 * An unconditional trailing bump would be correct but would bump on every memory
 * WRITE, where lithium only bumps on a memory PRESENCE transition
 * ({@code null <-> non-null}) — which is all {@code hasRequiredMemories} depends on.
 * Sensors and {@code LookAtTargetSink}/{@code MoveToTargetSink} rewrite present
 * memories every tick, so an unconditional bump would invalidate every cache entry
 * every tick and silently reduce the whole module to vanilla cost. The
 * before/after {@code hasMemoryValue} comparison below reproduces lithium's own
 * predicate exactly:
 * <ul>
 *   <li>{@code clear()} path — lithium bumps iff {@code slot.hasValue()} pre;
 *       here {@code after == false}, so {@code before != after} iff pre had a value.</li>
 *   <li>{@code set(v)} path — lithium bumps iff {@code slot.hasValue() == (value == null)},
 *       i.e. iff the slot was empty; here {@code after == true}, so
 *       {@code before != after} iff pre was empty.</li>
 *   <li>{@code slot == null} (memory not registered) — no mutation, no bump, both agree.</li>
 * </ul>
 * Cost is two extra {@code Brain.checkMemory} lookups per memory write; under
 * {@code AtomicMemoryStorage} those are array indexing.
 *
 * <h2>What is deliberately NOT wrapped</h2>
 * Lithium already bumps AFTER the mutation in the other three sites, so they need
 * no help and adding one would be pure over-invalidation:
 * {@code registerMemory} and {@code clearMemories} bump from
 * {@code @Inject(at = RETURN)}; {@code forgetOutdatedMemories} bumps from inside the
 * {@code @ModifyArg} consumer, after {@code slot.tick()} and only when the memory
 * actually expired. {@code forgetOutdatedMemories} in particular runs once per brain
 * per tick — an unconditional bump there would defeat the cache outright.
 * The third overload, {@code setMemoryInternal(MemoryMap$Value)}, delegates to the
 * two wrapped below and is covered transitively.
 *
 * <p>Nothing is needed for {@code BehaviorMixin}'s two plain cache fields: they are
 * read and written only by the thread ticking that entity
 * ({@code RegionKeyed.fabrolia$tryMarkRegionTick} + {@code TickGuard}) and
 * cross-tick publication rides the end-of-tick barrier. Their correctness depended
 * entirely on the counter.
 *
 * <p>Un-cancelling {@code memory_changes} also re-arms lithium's
 * {@code @ModifyArg(method = "forgetOutdatedMemories", at = INVOKE Collection.forEach)},
 * which has to survive Fabrolia's {@code entity/BrainMixin} swapping
 * {@code Brain.memories} for {@code AtomicMemoryStorage}. It does — the swap is a
 * runtime assignment from {@code @Inject(<init>, TAIL)} into a field still declared
 * {@code Map}, so {@code values()} still returns {@code Collection} and the INVOKE
 * descriptor is untouched. Pinned by
 * {@code LithiumAiTaskCancellationTest#lithiumForgetOutdatedMemoriesInjectionPointSurvivesTheStorageSwap}.
 *
 * <p>Casts to a lithium interface — {@code MixinCanceller} gates it behind
 * {@code !LITHIUM}.
 */
@Mixin(value = Brain.class, priority = 1600)
public abstract class FabroliaLithiumBrainMemoryBumpMixin {

    @Shadow
    public abstract boolean hasMemoryValue(MemoryModuleType<?> type);

    @Unique
    private void fabrolia$bumpIfPresenceChanged(boolean hadValue, MemoryModuleType<?> type) {
        if (hadValue != this.hasMemoryValue(type)) {
            ((MemoryModificationCounter) this).lithium$onMemoryModified();
        }
    }

    @WrapMethod(method = "setMemoryInternal(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;Ljava/lang/Object;J)V")
    private void fabrolia$bumpAfterSetWithExpiry(MemoryModuleType<?> type, Object value, long timeToLive,
                                                 Operation<Void> original) {
        boolean hadValue = this.hasMemoryValue(type);
        original.call(type, value, timeToLive);
        this.fabrolia$bumpIfPresenceChanged(hadValue, type);
    }

    @WrapMethod(method = "setMemoryInternal(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;Ljava/lang/Object;)V")
    private void fabrolia$bumpAfterSet(MemoryModuleType<?> type, Object value, Operation<Void> original) {
        boolean hadValue = this.hasMemoryValue(type);
        original.call(type, value);
        this.fabrolia$bumpIfPresenceChanged(hadValue, type);
    }

    @WrapMethod(method = "eraseMemory")
    private void fabrolia$bumpAfterErase(MemoryModuleType<?> type, Operation<Void> original) {
        boolean hadValue = this.hasMemoryValue(type);
        original.call(type);
        this.fabrolia$bumpIfPresenceChanged(hadValue, type);
    }
}
