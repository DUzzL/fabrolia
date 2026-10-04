package com.axalotl.fabrolia.common.regions;

import com.google.gson.JsonElement;
import com.mojang.brigadier.context.ContextChain;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.execution.UnboundEntryAction;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.commands.functions.PlainTextFunction;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link FunctionPlan} from the structural IR of one function
 * generation plus that generation's tags, predicates and advancement rewards.
 *
 * <p>Classification is an allowlist: a body is owner-local only when every
 * command of its call-graph closure is known to touch nothing but the bound
 * entity ({@code @s}), state written only by fenced Main code (constants,
 * storage, teams — read only), or packets to players near the entity.
 */
public final class FunctionPlanner {

    /** Everything the planner reads, all from ONE activated generation. */
    public interface Sources {
        FunctionIR.Function ir(Identifier id);
        CommandFunction<CommandSourceStack> function(Identifier id);
        Iterable<Identifier> functionIds();
        /** Member ids of a function tag, in tag order; null when unknown. */
        List<Identifier> tag(Identifier id);
        /** Encoded loot predicate, or null. */
        JsonElement predicate(String id);
        /** Reward function ids of every loaded advancement. */
        List<Identifier> rewardFunctions();
    }

    enum Self { NONE, PLAYER, NON_PLAYER, ANY;
        boolean playerPossible() { return this == PLAYER || this == ANY; }
    }

    record Ctx(Self self, boolean anchorSelf) {}

    private static final Identifier TICK = Identifier.withDefaultNamespace("tick");
    private static final Identifier LOAD = Identifier.withDefaultNamespace("load");
    private static final int LOCAL_OFFSET = 16;

    private final Sources sources;
    private final HashMap<Identifier, HashMap<Ctx, FunctionEffects>> memo = new HashMap<>();
    private final Set<String> visiting = new HashSet<>();

    private FunctionPlanner(Sources sources) {
        this.sources = sources;
    }

    public static FunctionPlan build(Sources sources) {
        return new FunctionPlanner(sources).plan();
    }

    // ------------------------------------------------------------------
    // Plan
    // ------------------------------------------------------------------

    private FunctionPlan plan() {
        // Owner-local proofs (C2 rewards, worker execute), per exact object.
        Set<CommandFunction<?>> ownerLocal = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Identifier> ownerLocalIds = new HashSet<>();
        for (Identifier id : sources.functionIds()) {
            FunctionIR.Function ir = sources.ir(id);
            CommandFunction<CommandSourceStack> fn = sources.function(id);
            if (ir == null || fn == null) continue;
            FunctionEffects fx = analyzeFunction(id, new Ctx(Self.PLAYER, true));
            if (!fx.isGlobal() && fx.fakeReads.isEmpty()) {
                ownerLocal.add(fn);
                ownerLocalIds.add(id);
            }
        }

        // Candidate roots.
        List<Identifier> tickIds = sources.tag(TICK);
        if (tickIds == null) tickIds = List.of();
        LinkedHashSet<Identifier> tickSet = new LinkedHashSet<>(tickIds);
        ArrayList<Identifier> loopIds = new ArrayList<>();
        for (Identifier id : sources.functionIds()) {
            FunctionIR.Function ir = sources.ir(id);
            if (ir == null || ir.macro() || tickSet.contains(id)) continue;
            for (FunctionIR.Line line : ir.lines()) {
                if (isSelfRearm(line, id)) {
                    loopIds.add(id);
                    break;
                }
            }
        }
        loopIds.sort(null);

        // First pass: per-line verdicts without the conflict rule.
        LinkedHashMap<Identifier, Draft> drafts = new LinkedHashMap<>();
        for (Identifier id : tickSet) drafts.put(id, draftRoot(id, FunctionPlan.Role.TICK));
        for (Identifier id : loopIds) drafts.putIfAbsent(id, draftRoot(id, FunctionPlan.Role.LOOP));

        // Main-resident code: #load, non-partitioned roots, every other
        // schedule target, rewards that are not owner-local.
        LinkedHashMap<String, String> mainWrites = new LinkedHashMap<>();
        LinkedHashMap<String, String> mainReads = new LinkedHashMap<>();
        LinkedHashSet<Identifier> mainResident = new LinkedHashSet<>();
        List<Identifier> load = sources.tag(LOAD);
        if (load != null) mainResident.addAll(load);
        for (Draft d : drafts.values()) if (!d.partitioned) mainResident.add(d.id);
        for (Identifier id : sources.functionIds()) {
            FunctionIR.Function ir = sources.ir(id);
            if (ir == null) continue;
            for (FunctionIR.Line line : ir.lines()) collectScheduleTargets(line, mainResident);
        }
        for (Identifier reward : sources.rewardFunctions()) {
            if (!ownerLocalIds.contains(reward)) mainResident.add(reward);
        }
        for (Draft d : drafts.values()) {
            if (d.partitioned) mainResident.remove(d.id);
        }
        for (Identifier id : mainResident) {
            FunctionEffects fx = analyzeFunction(id, new Ctx(Self.ANY, false));
            for (String w : fx.writes) mainWrites.putIfAbsent(w, id.toString());
            for (String r : fx.reads) mainReads.putIfAbsent(r, id.toString());
        }

        // Second pass: the conflict rule on island lines.
        ArrayList<FunctionPlan.RootPlan> tickRoots = new ArrayList<>();
        LinkedHashMap<Identifier, FunctionPlan.RootPlan> loops = new LinkedHashMap<>();
        ArrayList<String> notes = new ArrayList<>();
        for (Draft d : drafts.values()) {
            FunctionPlan.RootPlan root = finish(d, mainWrites, mainReads);
            if (d.role == FunctionPlan.Role.TICK) tickRoots.add(root);
            else loops.put(d.id, root);
        }
        if (!mainResident.isEmpty()) {
            notes.add("Main-resident functions considered by the conflict rule: " + mainResident.size());
        }
        return new FunctionPlan(List.copyOf(tickRoots), Collections.unmodifiableMap(loops),
                ownerLocal, Set.copyOf(ownerLocalIds), List.copyOf(notes));
    }

    private static final class Draft {
        Identifier id;
        FunctionPlan.Role role;
        CommandFunction<CommandSourceStack> function;
        boolean partitioned;
        String blocking;
        final ArrayList<DraftLine> lines = new ArrayList<>();
    }

    private static final class DraftLine {
        int index;
        FunctionIR.Line line;
        FunctionPlan.LineKind kind;
        String reason;
        int fanOutStage = -1;
        EntitySelector fanOut;
        FunctionEffects fx;
        ContextChain<CommandSourceStack> chain;
        UnboundEntryAction<CommandSourceStack> entry;
    }

    private Draft draftRoot(Identifier id, FunctionPlan.Role role) {
        Draft d = new Draft();
        d.id = id;
        d.role = role;
        d.function = sources.function(id);
        FunctionIR.Function ir = sources.ir(id);
        if (ir == null || d.function == null) {
            d.blocking = id + ": not analyzed (function absent from this generation)";
            return d;
        }
        if (ir.macro() || !(d.function instanceof PlainTextFunction<CommandSourceStack> plain)) {
            d.blocking = id + ": macro function";
            return d;
        }
        int rearms = 0;
        for (int i = 0; i < ir.lines().size(); i++) {
            FunctionIR.Line line = ir.lines().get(i);
            DraftLine dl = new DraftLine();
            dl.index = i;
            dl.line = line;
            dl.entry = plain.entries().get(i);
            dl.chain = FunctionIR.chainOf(dl.entry);
            classifyRootLine(id, role, line, dl);
            if (dl.kind == FunctionPlan.LineKind.REARM) rearms++;
            if (dl.kind == FunctionPlan.LineKind.GLOBAL && d.blocking == null) {
                d.blocking = id + ":" + line.sourceLine() + ": " + dl.reason;
            }
            d.lines.add(dl);
        }
        if (d.blocking == null && role == FunctionPlan.Role.LOOP && rearms != 1) {
            d.blocking = id + ": " + rearms + " self re-arm lines";
        }
        d.partitioned = d.blocking == null;
        return d;
    }

    private FunctionPlan.RootPlan finish(Draft d, Map<String, String> mainWrites,
                                         Map<String, String> mainReads) {
        ArrayList<FunctionPlan.LinePlan> lines = new ArrayList<>(d.lines.size());
        for (DraftLine dl : d.lines) {
            FunctionPlan.LineKind kind = dl.kind;
            String reason = dl.reason;
            ArrayList<String> accepted = new ArrayList<>();
            if (d.partitioned && kind == FunctionPlan.LineKind.PARTITION) {
                String conflict = conflict(dl.fx, mainWrites, mainReads);
                if (conflict != null) {
                    kind = FunctionPlan.LineKind.ESCALATE;
                    reason = conflict;
                }
                for (String r : dl.fx.reads) {
                    String writer = mainWrites.get(r);
                    if (writer != null) accepted.add(r + " (" + writer + ")");
                }
            }
            lines.add(new FunctionPlan.LinePlan(dl.index, dl.line.sourceLine(), dl.line.text(),
                    kind, reason, dl.fanOutStage, dl.fanOut,
                    dl.fx == null ? List.of() : List.copyOf(dl.fx.fakeReads),
                    dl.fx != null && dl.fx.playerSnapshot, List.copyOf(accepted),
                    dl.chain, dl.entry));
        }
        return new FunctionPlan.RootPlan(d.id, d.function, d.role, d.partitioned,
                d.blocking, List.copyOf(lines));
    }

    /** A partition line WRITING state that Main-resident code reads or writes. */
    private static String conflict(FunctionEffects fx, Map<String, String> mainWrites,
                                   Map<String, String> mainReads) {
        for (String w : fx.writes) {
            String other = touched(w, mainWrites);
            if (other != null) return "writes " + w + " also written by Main-resident " + other;
            other = touched(w, mainReads);
            if (other != null) return "writes " + w + " also read by Main-resident " + other;
        }
        return null;
    }

    private static String touched(String key, Map<String, String> main) {
        String exact = main.get(key);
        if (exact != null) return exact;
        int colon = key.indexOf(':');
        if (colon > 0) {
            String family = key.substring(0, colon + 1);
            if (key.endsWith(":*")) {
                for (Map.Entry<String, String> e : main.entrySet()) {
                    if (e.getKey().startsWith(family)) return e.getValue();
                }
            }
            return main.get(family + "*");
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Root lines
    // ------------------------------------------------------------------

    static boolean isSelfRearm(FunctionIR.Line line, Identifier self) {
        if (line.stages().size() != 1) return false;
        FunctionIR.Stage s = line.stages().getFirst();
        List<String> lits = s.literals();
        if (lits.size() < 2 || !"schedule".equals(lits.get(0)) || !"function".equals(lits.get(1))) {
            return false;
        }
        if (s.hasLiteral("append")) return false;
        FunctionIR.Node fn = s.arg("function");
        FunctionIR.Node time = s.arg("time");
        if (fn == null || time == null || !(time.value() instanceof Integer ticks) || ticks != 1) {
            return false;
        }
        Identifier target = Identifier.tryParse(fn.text());
        return target != null && target.equals(self);
    }

    private void classifyRootLine(Identifier root, FunctionPlan.Role role,
                                  FunctionIR.Line line, DraftLine dl) {
        String where = root + ":" + line.sourceLine();
        if (isSelfRearm(line, root)) {
            if (role == FunctionPlan.Role.LOOP) {
                dl.kind = FunctionPlan.LineKind.REARM;
            } else {
                dl.kind = FunctionPlan.LineKind.GLOBAL;
                dl.reason = "a #tick root re-arms itself";
            }
            return;
        }
        List<FunctionIR.Stage> stages = line.stages();
        int fan = -1;
        for (int i = 0; i < stages.size() - 1; i++) {
            FunctionIR.Stage s = stages.get(i);
            if ("as".equals(s.head()) && FunctionIR.selectorOf(s.arg("targets")) != null) {
                fan = i;
                break;
            }
        }
        if (fan < 0) {
            dl.kind = FunctionPlan.LineKind.GLOBAL;
            dl.reason = "root-level command without an entity fan-out";
            return;
        }
        // Prefix stages run once per partition with the game-loop source.
        FunctionEffects prefix = new FunctionEffects();
        Ctx ctx = new Ctx(Self.NONE, false);
        for (int i = 0; i < fan; i++) {
            FunctionIR.Stage st = stages.get(i);
            if (("if".equals(st.head()) || "unless".equals(st.head())) && st.hasLiteral("function")) {
                // CustomModifierExecutor: not a plain redirect the partition can replay.
                prefix.global(where, "execute " + st.head() + " function before the fan-out");
                break;
            }
            ctx = modifier(st, ctx, prefix, where, true);
        }
        if (prefix.isGlobal()) {
            dl.kind = FunctionPlan.LineKind.GLOBAL;
            dl.reason = prefix.globalReason;
            return;
        }
        FunctionIR.Stage fanStage = stages.get(fan);
        FunctionIR.Node targets = fanStage.arg("targets");
        EntitySelector selector = FunctionIR.selectorOf(targets);
        FunctionSelectorOptions opts = FunctionSelectorOptions.parse(targets.text());
        String fanProblem = fanOutProblem(selector, opts);
        if (fanProblem != null) {
            dl.kind = FunctionPlan.LineKind.GLOBAL;
            dl.reason = fanProblem;
            return;
        }
        Self self = selfOf(opts);
        FunctionEffects fx = new FunctionEffects();
        fx.addAll(prefix);
        selectorOptions(opts, self, true, fx, where);
        if (fx.isGlobal()) {
            dl.kind = FunctionPlan.LineKind.GLOBAL;
            dl.reason = fx.globalReason;
            return;
        }
        for (int i = fan + 1; i < stages.size(); i++) {
            if ("return".equals(stages.get(i).head())) {
                dl.kind = FunctionPlan.LineKind.GLOBAL;
                dl.reason = "return inside a root line ends the whole root";
                return;
            }
        }
        dl.fanOutStage = fan;
        dl.fanOut = selector;
        Ctx body = new Ctx(self, false);
        stages(stages, fan + 1, body, fx, where, true);
        dl.fx = fx;
        if (fx.isGlobal()) {
            dl.kind = FunctionPlan.LineKind.ESCALATE;
            dl.reason = fx.globalReason;
        } else {
            dl.kind = FunctionPlan.LineKind.PARTITION;
        }
    }

    private static String fanOutProblem(EntitySelector selector, FunctionSelectorOptions opts) {
        if (opts.kind() != 'a' && opts.kind() != 'e') {
            return "fan-out " + (opts.kind() == '\0' ? "by name/UUID" : "@" + opts.kind())
                    + " needs a global view";
        }
        if (selector.getMaxResults() != Integer.MAX_VALUE || opts.has("limit")) {
            return "fan-out with limit needs a global view";
        }
        if (opts.has("sort")) return "fan-out with sort needs a global view";
        return null;
    }

    private static Self selfOf(FunctionSelectorOptions opts) {
        if (opts.kind() == 'a' || opts.kind() == 'p' || opts.kind() == 'r') return Self.PLAYER;
        if (opts.kind() == 's') return Self.ANY;
        for (FunctionSelectorOptions.Option o : opts.all("type")) {
            if (o.negated() || o.value().startsWith("#")) continue;
            String t = o.value().contains(":") ? o.value() : "minecraft:" + o.value();
            return "minecraft:player".equals(t) ? Self.PLAYER : Self.NON_PLAYER;
        }
        return Self.ANY;
    }

    // ------------------------------------------------------------------
    // Function closure
    // ------------------------------------------------------------------

    FunctionEffects analyzeFunction(Identifier id, Ctx ctx) {
        HashMap<Ctx, FunctionEffects> byCtx = memo.computeIfAbsent(id, k -> new HashMap<>());
        FunctionEffects cached = byCtx.get(ctx);
        if (cached != null) return cached;
        FunctionEffects fx = new FunctionEffects();
        String key = id + "|" + ctx;
        if (!visiting.add(key)) {
            fx.global(id.toString(), "recursive function call");
            return fx;
        }
        try {
            FunctionIR.Function ir = sources.ir(id);
            if (ir == null) {
                fx.global(id.toString(), "unknown function");
            } else if (ir.macro()) {
                fx.global(id.toString(), "macro function");
            } else {
                for (FunctionIR.Line line : ir.lines()) {
                    stages(line.stages(), 0, ctx, fx, id + ":" + line.sourceLine(), false);
                }
            }
        } finally {
            visiting.remove(key);
        }
        byCtx.put(ctx, fx);
        return fx;
    }

    private void call(String nameText, Ctx ctx, FunctionEffects fx, String where) {
        boolean tag = nameText.startsWith("#");
        Identifier id = Identifier.tryParse(tag ? nameText.substring(1) : nameText);
        if (id == null) {
            fx.global(where, "unparsable function id " + nameText);
            return;
        }
        if (tag) {
            List<Identifier> members = sources.tag(id);
            if (members == null) {
                fx.global(where, "unknown function tag #" + id);
                return;
            }
            for (Identifier m : members) fx.addAll(analyzeFunction(m, ctx));
        } else {
            fx.addAll(analyzeFunction(id, ctx));
        }
    }

    // ------------------------------------------------------------------
    // Stages
    // ------------------------------------------------------------------

    private void stages(List<FunctionIR.Stage> stages, int from, Ctx ctx, FunctionEffects fx,
                        String where, boolean rootBody) {
        Ctx c = ctx;
        for (int i = from; i < stages.size(); i++) {
            FunctionIR.Stage s = stages.get(i);
            if (s.executable()) {
                command(s, c, fx, where, rootBody);
            } else {
                c = modifier(s, c, fx, where, false);
            }
            // Keep classifying after a global stage: Main-resident write sets
            // (conflict rule) need every determinable write of the closure.
        }
    }

    /** One execute modifier stage; returns the context after it. */
    private Ctx modifier(FunctionIR.Stage s, Ctx ctx, FunctionEffects fx, String where,
                         boolean prefix) {
        String head = s.head();
        switch (head) {
            case "run", "align", "anchored", "return" -> {
                return ctx;
            }
            case "as" -> {
                if (!selfSelector(s.arg("targets"), ctx, fx, where, "execute as")) return ctx;
                return ctx;
            }
            case "at" -> {
                if (!selfSelector(s.arg("targets"), ctx, fx, where, "execute at")) return ctx;
                return new Ctx(ctx.self(), true);
            }
            case "positioned" -> {
                if (s.hasLiteral("as")) {
                    if (!selfSelector(s.arg("targets"), ctx, fx, where, "positioned as")) return ctx;
                    return new Ctx(ctx.self(), true);
                }
                if (s.hasLiteral("over")) {
                    if (!ctx.anchorSelf()) fx.global(where, "positioned over away from @s");
                    return ctx;
                }
                FunctionIR.Node pos = s.arg("pos");
                return new Ctx(ctx.self(), ctx.anchorSelf() && pos != null && localOffset(pos.text()));
            }
            case "rotated" -> {
                if (s.hasLiteral("as")) selfSelector(s.arg("targets"), ctx, fx, where, "rotated as");
                return ctx;
            }
            case "facing" -> {
                if (s.hasLiteral("entity")) selfSelector(s.arg("targets"), ctx, fx, where, "facing entity");
                return ctx;
            }
            case "if", "unless" -> {
                condition(s, ctx, fx, where);
                return ctx;
            }
            case "store" -> {
                store(s, ctx, fx, where);
                return ctx;
            }
            default -> {
                fx.global(where, "execute " + head + (prefix ? " before the fan-out" : ""));
                return ctx;
            }
        }
    }

    private void condition(FunctionIR.Stage s, Ctx ctx, FunctionEffects fx, String where) {
        List<String> lits = s.literals();
        String kind = lits.size() > 1 ? lits.get(1) : "";
        switch (kind) {
            case "entity" -> selfSelector(s.arg("entities"), ctx, fx, where, "if entity");
            case "score" -> {
                holderRead(s.arg("target"), s.arg("targetObjective"), ctx, fx, where);
                FunctionIR.Node source = s.arg("source");
                if (source != null) holderRead(source, s.arg("sourceObjective"), ctx, fx, where);
            }
            case "predicate" -> predicate(s.arg("predicate"), ctx, ctx.anchorSelf(), fx, where);
            case "data" -> {
                String type = lits.size() > 2 ? lits.get(2) : "";
                switch (type) {
                    case "storage" -> fx.read("storage:" + textOf(s.arg("source")));
                    case "entity" -> {
                        if (selfSelector(s.arg("source"), ctx, fx, where, "if data entity")) {
                            selfData(ctx, fx, where, false);
                        }
                    }
                    default -> fx.global(where, "if data " + type);
                }
            }
            case "items" -> {
                String type = lits.size() > 2 ? lits.get(2) : "";
                if ("entity".equals(type)) {
                    if (selfSelector(s.arg("source"), ctx, fx, where, "if items entity")) {
                        fx.read("inventory");
                    }
                } else {
                    fx.global(where, "if items " + type);
                }
            }
            case "function" -> call(textOf(s.arg("name")), ctx, fx, where);
            case "dimension" -> { }
            default -> fx.global(where, "if " + kind);
        }
    }

    private void store(FunctionIR.Stage s, Ctx ctx, FunctionEffects fx, String where) {
        List<String> lits = s.literals();
        String type = lits.size() > 2 ? lits.get(2) : "";
        switch (type) {
            case "score" -> holderWrite(s.arg("targets"), s.arg("objective"), ctx, fx, where);
            case "entity" -> {
                fx.write("entitydata");
                fx.global(where, "execute store entity reloads the whole entity");
            }
            default -> fx.global(where, "execute store " + type);
        }
    }

    // ------------------------------------------------------------------
    // Executable commands
    // ------------------------------------------------------------------

    private void command(FunctionIR.Stage s, Ctx ctx, FunctionEffects fx, String where,
                         boolean rootBody) {
        String head = s.head();
        List<String> lits = s.literals();
        switch (head) {
            case "if", "unless" -> condition(s, ctx, fx, where);
            case "function" -> {
                if (s.arg("arguments") != null || s.hasLiteral("with")) {
                    fx.global(where, "function call with macro arguments");
                } else {
                    call(textOf(s.arg("name")), ctx, fx, where);
                }
            }
            case "return" -> {
                // Frame-local inside a callee; a root line's own return is
                // rejected by classifyRootLine (it would end the whole root).
            }
            case "scoreboard" -> scoreboard(s, lits, ctx, fx, where);
            case "tag" -> {
                if (s.hasLiteral("add") || s.hasLiteral("remove")) {
                    fx.write("tag:" + textOf(s.arg("name")));
                }
                selfSelector(s.arg("targets"), ctx, fx, where, "tag");
            }
            case "advancement" -> {
                // revoke @s: C1 runs it synchronously on the owner and it has
                // no global tail. grant also announces to chat and runs reward
                // functions/loot — global tails C2 routes only at its own award
                // point, so a granting body escalates.
                FunctionIR.Node adv = s.hasLiteral("only") ? s.arg("advancement") : null;
                fx.write("adv:" + (adv != null ? adv.text() : "*"));
                if (selfSelector(s.arg("targets"), ctx, fx, where, "advancement")
                        && s.hasLiteral("grant")) {
                    fx.global(where, "advancement grant announces and runs rewards outside the owner");
                }
            }
            case "clear", "give", "enchant" -> {
                // Inventory triggers may award advancements (C1 on the owner).
                fx.write("adv:*");
                if (selfSelector(s.arg("targets"), ctx, fx, where, head)) fx.write("inventory");
            }
            case "effect" -> {
                fx.write("adv:*");
                if (selfSelector(s.arg("targets"), ctx, fx, where, head)) fx.write("effects");
            }
            case "xp", "experience" -> {
                if (selfSelector(s.arg("target") != null ? s.arg("target") : s.arg("targets"),
                        ctx, fx, where, head)) {
                    if (s.hasLiteral("query")) fx.read("xp");
                    else fx.write("xp");
                }
            }
            case "attribute" -> {
                if (selfSelector(s.arg("target"), ctx, fx, where, head)) fx.write("attribute");
            }
            case "item" -> item(s, lits, ctx, fx, where);
            case "trigger" -> {
                if (ctx.self() != Self.PLAYER) {
                    fx.global(where, "trigger from a possibly non-player source");
                } else {
                    fx.write("score:" + textOf(s.arg("objective")));
                }
            }
            case "tellraw", "title" -> {
                if (!selfSelector(s.arg("targets"), ctx, fx, where, head)) return;
                FunctionIR.Node message = s.arg("message") != null ? s.arg("message") : s.arg("title");
                if (message != null && dynamicComponent(message.text())) {
                    fx.global(where, head + " component reads scores/selectors/NBT");
                }
            }
            case "playsound", "stopsound" -> selfSelector(s.arg("targets"), ctx, fx, where, head);
            case "particle" -> particle(s, ctx, fx, where);
            case "data" -> data(s, lits, ctx, fx, where);
            case "team" -> {
                // Global, but its write set still feeds the conflict rule.
                fx.write("team");
                fx.global(where, "command team");
            }
            default -> fx.global(where, "command " + head);
        }
    }

    private void scoreboard(FunctionIR.Stage s, List<String> lits, Ctx ctx, FunctionEffects fx,
                            String where) {
        if (lits.size() < 3 || !"players".equals(lits.get(1))) {
            fx.global(where, "scoreboard " + (lits.size() > 1 ? lits.get(1) : ""));
            return;
        }
        switch (lits.get(2)) {
            case "set", "add", "remove", "enable" ->
                    holderWrite(s.arg("targets"), s.arg("objective"), ctx, fx, where);
            case "reset" -> {
                FunctionIR.Node objective = s.arg("objective");
                if (objective == null) {
                    holderWriteKey(s.arg("targets"), "score:*", ctx, fx, where);
                } else {
                    holderWrite(s.arg("targets"), objective, ctx, fx, where);
                }
            }
            case "get" -> holderRead(s.arg("target"), s.arg("objective"), ctx, fx, where);
            case "operation" -> {
                FunctionIR.Node op = s.arg("operation");
                holderWrite(s.arg("targets"), s.arg("targetObjective"), ctx, fx, where);
                holderRead(s.arg("targets"), s.arg("targetObjective"), ctx, fx, where);
                FunctionIR.Node source = s.arg("source");
                if (op != null && "><".equals(op.text())) {
                    holderWrite(source, s.arg("sourceObjective"), ctx, fx, where);
                }
                holderRead(source, s.arg("sourceObjective"), ctx, fx, where);
            }
            default -> fx.global(where, "scoreboard players " + lits.get(2));
        }
    }

    private void item(FunctionIR.Stage s, List<String> lits, Ctx ctx, FunctionEffects fx,
                      String where) {
        if (lits.size() < 3 || !"replace".equals(lits.get(1)) || !"entity".equals(lits.get(2))) {
            fx.global(where, "item " + String.join(" ", lits.subList(1, Math.min(lits.size(), 3))));
            return;
        }
        if (!selfSelector(s.arg("targets"), ctx, fx, where, "item replace entity")) return;
        fx.write("inventory");
        if (s.hasLiteral("from")) {
            int from = lits.indexOf("from");
            String type = from + 1 < lits.size() ? lits.get(from + 1) : "";
            if (!"entity".equals(type)) {
                fx.global(where, "item replace from " + type);
            } else if (selfSelector(s.arg("source"), ctx, fx, where, "item replace from entity")) {
                fx.read("inventory");
            }
        }
        if (s.arg("modifier") != null) fx.global(where, "item modifier");
    }

    private void data(FunctionIR.Stage s, List<String> lits, Ctx ctx, FunctionEffects fx,
                      String where) {
        if (lits.size() < 3) {
            fx.global(where, "data");
            return;
        }
        String op = lits.get(1);
        String targetType = lits.get(2);
        boolean write = !"get".equals(op);
        switch (targetType) {
            case "storage" -> {
                if (write) {
                    fx.write("storage:" + textOf(s.arg("target")));
                    fx.global(where, "data " + op + " storage writes global storage");
                    return;
                }
                fx.read("storage:" + textOf(s.arg("target")));
            }
            case "entity" -> {
                if (write) {
                    // Entity.load of the merged tag: Pos teleports, Team joins
                    // teams, UUID/type data rewrites — never owner-local.
                    fx.write("entitydata");
                    fx.global(where, "data " + op + " entity reloads the whole entity");
                    return;
                }
                if (!selfSelector(s.arg("target"), ctx, fx, where, "data entity")) return;
                if (!selfData(ctx, fx, where, false)) return;
            }
            default -> {
                fx.global(where, "data " + op + " " + targetType);
                return;
            }
        }
        // data modify ... from|string <type> <source>
        int from = Math.max(lits.indexOf("from"), lits.indexOf("string"));
        if (from >= 0) {
            String type = from + 1 < lits.size() ? lits.get(from + 1) : "";
            switch (type) {
                case "storage" -> fx.read("storage:" + textOf(s.arg("source")));
                case "entity" -> {
                    if (selfSelector(s.arg("source"), ctx, fx, where, "data from entity")) {
                        selfData(ctx, fx, where, false);
                    }
                }
                default -> fx.global(where, "data source " + type);
            }
        }
    }

    private void particle(FunctionIR.Stage s, Ctx ctx, FunctionEffects fx, String where) {
        FunctionIR.Node pos = s.arg("pos");
        if (!ctx.anchorSelf() || (pos != null && !localOffset(pos.text()))) {
            fx.global(where, "particle away from the executing entity");
            return;
        }
        if (s.hasLiteral("force")) {
            fx.global(where, "force particle reaches players 512 blocks away");
            return;
        }
        FunctionIR.Node viewers = s.arg("viewers");
        if (viewers == null) {
            fx.playerSnapshot = true;
        } else {
            selfSelector(viewers, ctx, fx, where, "particle viewers");
        }
    }

    // ------------------------------------------------------------------
    // Selectors, holders, predicates
    // ------------------------------------------------------------------

    /**
     * {@code @s}-only selector; records its option reads either way (Main
     * write/read sets). False (and global) when it may reach another entity.
     */
    private boolean selfSelector(FunctionIR.Node node, Ctx ctx, FunctionEffects fx, String where,
                                 String what) {
        if (node == null) {
            fx.global(where, what + " without a target");
            return false;
        }
        FunctionSelectorOptions opts = FunctionSelectorOptions.parse(node.text());
        String before = fx.globalReason;
        selectorOptions(opts, ctx.self() == Self.NONE ? Self.ANY : ctx.self(), false, fx, where);
        if (opts.kind() != 's') {
            fx.global(where, what + " " + node.text() + " reaches entities other than @s");
            return false;
        }
        if (ctx.self() == Self.NONE) {
            fx.global(where, what + " @s without a bound entity");
            return false;
        }
        return fx.globalReason == before;
    }

    private void selectorOptions(FunctionSelectorOptions opts, Self self, boolean fanOut,
                                 FunctionEffects fx, String where) {
        Self effective = self;
        for (FunctionSelectorOptions.Option o : opts.all("type")) {
            if (!o.negated() && !o.value().startsWith("#")) {
                String t = o.value().contains(":") ? o.value() : "minecraft:" + o.value();
                effective = "minecraft:player".equals(t) ? Self.PLAYER : Self.NON_PLAYER;
            }
        }
        for (FunctionSelectorOptions.Option o : opts.options()) {
            switch (o.key()) {
                case "tag" -> fx.read("tag:" + o.value());
                case "scores" -> {
                    for (String obj : FunctionSelectorOptions.scoreObjectives(o.value())) {
                        fx.read("score:" + obj);
                    }
                }
                case "team" -> fx.read("team");
                case "advancements" -> {
                    for (String adv : FunctionSelectorOptions.scoreObjectives(o.value())) {
                        fx.read("adv:" + adv);
                    }
                }
                case "predicate" -> predicate(o.value(), effective, fx, where);
                case "nbt" -> {
                    if (effective.playerPossible()) {
                        fx.global(where, "nbt= on a possible player serializes the whole player");
                        return;
                    }
                    fx.read("entitydata");
                }
                case "name", "type", "gamemode", "level", "x_rotation", "y_rotation",
                     "distance", "x", "y", "z", "dx", "dy", "dz" -> { }
                case "limit", "sort" -> {
                    if (fanOut) {
                        fx.global(where, "fan-out " + o.key() + " needs a global view");
                        return;
                    }
                }
                default -> {
                    fx.global(where, "selector option " + o.key());
                    return;
                }
            }
        }
    }

    private void predicate(String id, Self self, FunctionEffects fx, String where) {
        FunctionPredicateEffects.classify(FunctionSelectorOptions.unquote(id), sources::predicate,
                self.playerPossible(), true, fx, where);
    }

    private void predicate(FunctionIR.Node node, Ctx ctx, boolean originAtSelf,
                           FunctionEffects fx, String where) {
        if (node == null) {
            fx.global(where, "predicate without id");
            return;
        }
        if (ctx.self() == Self.NONE) {
            fx.global(where, "predicate without a bound entity");
            return;
        }
        String text = node.text();
        if (text.startsWith("{") || text.startsWith("[")) {
            fx.global(where, "inline predicate");
            return;
        }
        FunctionPredicateEffects.classify(text, sources::predicate,
                ctx.self().playerPossible(), originAtSelf, fx, where);
    }

    private boolean selfData(Ctx ctx, FunctionEffects fx, String where, boolean write) {
        if (ctx.self().playerPossible()) {
            fx.global(where, (write ? "NBT write" : "full NBT read") + " of a possible player");
            return false;
        }
        if (write) fx.write("entitydata");
        else fx.read("entitydata");
        return true;
    }

    private void holderRead(FunctionIR.Node holder, FunctionIR.Node objective, Ctx ctx,
                            FunctionEffects fx, String where) {
        if (holder == null || objective == null) {
            fx.global(where, "score read without holder/objective");
            return;
        }
        String text = holder.text();
        String obj = objective.text();
        if (text.startsWith("@")) {
            if (selfSelector(holder, ctx, fx, where, "score holder")) fx.read("score:" + obj);
        } else if ("*".equals(text)) {
            fx.global(where, "score read of every holder");
        } else {
            fx.fakeRead(text, obj);
        }
    }

    private void holderWrite(FunctionIR.Node holder, FunctionIR.Node objective, Ctx ctx,
                             FunctionEffects fx, String where) {
        if (objective == null) {
            fx.global(where, "score write without objective");
            return;
        }
        holderWriteKey(holder, "score:" + objective.text(), ctx, fx, where);
    }

    private void holderWriteKey(FunctionIR.Node holder, String key, Ctx ctx, FunctionEffects fx,
                                String where) {
        if (holder == null) {
            fx.global(where, "score write without holder");
            return;
        }
        String text = holder.text();
        if (text.startsWith("@")) {
            fx.write(key);
            selfSelector(holder, ctx, fx, where, "score holder");
        } else {
            if (key.startsWith("score:")) fx.write("fake:" + text + ":" + key.substring(6));
            fx.global(where, "score write to fake player/name " + text);
        }
    }

    // ------------------------------------------------------------------
    // Text helpers
    // ------------------------------------------------------------------

    private static String textOf(FunctionIR.Node node) {
        return node == null ? "" : node.text();
    }

    /** Relative/local coordinates within {@link #LOCAL_OFFSET} blocks. */
    static boolean localOffset(String coords) {
        String[] parts = coords.trim().split("\\s+");
        for (String p : parts) {
            if (p.isEmpty()) continue;
            char c = p.charAt(0);
            if (c != '~' && c != '^') return false;
            if (p.length() > 1) {
                try {
                    if (Math.abs(Double.parseDouble(p.substring(1))) > LOCAL_OFFSET) return false;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Any score/selector/NBT text component part (conservative text scan). */
    static boolean dynamicComponent(String text) {
        return text.contains("score") || text.contains("selector") || text.contains("nbt")
                || text.contains("entity");
    }

    private void collectScheduleTargets(FunctionIR.Line line, Set<Identifier> out) {
        for (FunctionIR.Stage s : line.stages()) {
            List<String> lits = s.literals();
            if (lits.size() >= 2 && "schedule".equals(lits.get(0)) && "function".equals(lits.get(1))
                    && !s.hasLiteral("clear")) {
                FunctionIR.Node fn = s.arg("function");
                if (fn == null) continue;
                String text = fn.text();
                boolean tag = text.startsWith("#");
                Identifier id = Identifier.tryParse(tag ? text.substring(1) : text);
                if (id == null) continue;
                if (!tag) {
                    out.add(id);
                } else {
                    List<Identifier> members = sources.tag(id);
                    if (members != null) out.addAll(members);
                }
            }
        }
    }
}
