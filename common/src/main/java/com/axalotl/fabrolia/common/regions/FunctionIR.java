package com.axalotl.fabrolia.common.regions;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.execution.UnboundEntryAction;
import net.minecraft.commands.execution.tasks.BuildContexts;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.commands.functions.PlainTextFunction;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Structural view of ONE function exactly as vanilla parsed it.
 *
 * <p>Built on the reload parse thread from the {@link CommandFunction} returned
 * by {@code CommandFunction.fromLines}: every entry of a {@link PlainTextFunction}
 * is a {@link BuildContexts.Unbound} whose {@link ContextChain} is walked stage
 * by stage. Each stage keeps its node names, the exact source text of every node
 * (taken from the parsed range, so selector options are the options of THIS
 * parse) and the typed argument value. Nothing here is re-parsed later: the
 * planner classifies these records and the executor runs the very same chains.
 */
public final class FunctionIR {

    private FunctionIR() {}

    /** One parsed node: literal name, or argument name + its exact text/value. */
    public record Node(String name, boolean literal, String text, Object value) {}

    /** One ContextChain stage (a redirect modifier stage or the executable). */
    public record Stage(List<Node> nodes, boolean forked, boolean executable) {
        /** First literal, skipping the leading {@code execute} literal of stage 0. */
        public String head() {
            for (Node n : nodes) {
                if (n.literal() && !"execute".equals(n.name())) return n.name();
            }
            return nodes.isEmpty() ? "" : nodes.getFirst().name();
        }

        public Node arg(String name) {
            for (Node n : nodes) {
                if (!n.literal() && n.name().equals(name)) return n;
            }
            return null;
        }

        public boolean hasLiteral(String name) {
            for (Node n : nodes) {
                if (n.literal() && n.name().equals(name)) return true;
            }
            return false;
        }

        /** Literal names in order (without the leading {@code execute}). */
        public List<String> literals() {
            ArrayList<String> out = new ArrayList<>(nodes.size());
            for (Node n : nodes) {
                if (n.literal() && !"execute".equals(n.name())) out.add(n.name());
            }
            return out;
        }
    }

    /** One command entry: its 1-based source line, text and stages. */
    public record Line(int sourceLine, String text, List<Stage> stages) {}

    /** {@code macro} functions keep no lines: they are never analyzable. */
    public record Function(Identifier id, boolean macro, List<Line> lines) {}

    /** The exact parsed chain of one plain-text entry. */
    @SuppressWarnings("unchecked")
    public static ContextChain<CommandSourceStack> chainOf(UnboundEntryAction<?> entry) {
        if (!(entry instanceof BuildContexts<?> contexts)) {
            throw new IllegalStateException("function entry is not a parsed command: " + entry);
        }
        return (ContextChain<CommandSourceStack>) contexts.command;
    }

    public static Function of(Identifier id, CommandFunction<?> function, List<String> sourceLines) {
        if (!(function instanceof PlainTextFunction<?> plain)) {
            return new Function(id, true, List.of());
        }
        int[] lineNumbers = entryLineNumbers(sourceLines);
        List<? extends UnboundEntryAction<?>> entries = plain.entries();
        ArrayList<Line> lines = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            UnboundEntryAction<?> entry = entries.get(i);
            int sourceLine = i < lineNumbers.length ? lineNumbers[i] : -1;
            lines.add(lineOf(sourceLine, entry));
        }
        return new Function(id, false, List.copyOf(lines));
    }

    private static Line lineOf(int sourceLine, UnboundEntryAction<?> entry) {
        ContextChain<CommandSourceStack> chain = chainOf(entry);
        ArrayList<Stage> stages = new ArrayList<>(4);
        ContextChain<CommandSourceStack> c = chain;
        while (true) {
            CommandContext<CommandSourceStack> ctx = c.getTopContext();
            boolean executable = c.getStage() == ContextChain.Stage.EXECUTE;
            stages.add(stageOf(ctx, executable));
            if (executable) break;
            c = c.nextStage();
        }
        return new Line(sourceLine, entry.toString(), List.copyOf(stages));
    }

    /** Brigadier keeps parsed arguments in a private map; a parser may store null. */
    private static final java.lang.reflect.Field ARGUMENTS;

    static {
        try {
            ARGUMENTS = CommandContext.class.getDeclaredField("arguments");
            ARGUMENTS.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, com.mojang.brigadier.context.ParsedArgument<?, ?>> arguments(
            CommandContext<?> ctx) {
        try {
            return (java.util.Map<String, com.mojang.brigadier.context.ParsedArgument<?, ?>>) ARGUMENTS.get(ctx);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("brigadier CommandContext.arguments not readable", e);
        }
    }

    private static Stage stageOf(CommandContext<CommandSourceStack> ctx, boolean executable) {
        List<ParsedCommandNode<CommandSourceStack>> parsed = ctx.getNodes();
        ArrayList<Node> nodes = new ArrayList<>(parsed.size());
        String input = ctx.getInput();
        java.util.Map<String, com.mojang.brigadier.context.ParsedArgument<?, ?>> args = arguments(ctx);
        for (ParsedCommandNode<CommandSourceStack> p : parsed) {
            String name = p.getNode().getName();
            String text = p.getRange().get(input);
            if (p.getNode() instanceof ArgumentCommandNode<?, ?>) {
                var arg = args.get(name);
                nodes.add(new Node(name, false, text, arg == null ? null : arg.getResult()));
            } else {
                nodes.add(new Node(name, true, text, null));
            }
        }
        return new Stage(List.copyOf(nodes), ctx.isForked(), executable);
    }

    /**
     * 1-based source line of each command entry, mirroring
     * {@code CommandFunction.fromLines}: blank and {@code #} lines produce no
     * entry; a trailing backslash joins the next line into the same entry.
     */
    static int[] entryLineNumbers(List<String> sourceLines) {
        int[] out = new int[sourceLines.size()];
        int n = 0;
        for (int i = 0; i < sourceLines.size(); i++) {
            int lineNumber = i + 1;
            String line = sourceLines.get(i).trim();
            while (line.endsWith("\\") && i + 1 < sourceLines.size()) {
                line = line.substring(0, line.length() - 1) + sourceLines.get(++i).trim();
            }
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            out[n++] = lineNumber;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** Typed selector of an argument node, or null. */
    public static EntitySelector selectorOf(Node node) {
        return node != null && node.value() instanceof EntitySelector s ? s : null;
    }
}
