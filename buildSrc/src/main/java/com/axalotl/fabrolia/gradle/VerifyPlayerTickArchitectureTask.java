package com.axalotl.fabrolia.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Connects the semantic ASM/JUnit proof to the exact Fabric archive.
 *
 * <p>The common test suite inspects the compiled common output. This task reads the
 * archive once, verifies that every owned class/resource in it is byte-identical to
 * the tested output, and repeats Mixin's runtime-only static-method visibility rule
 * against the packaged bytecode. Consequently an up-to-date test result cannot be
 * paired with a stale, concurrently replaced, or differently compiled JAR.</p>
 */
@DisableCachingByDefault(because = "The archive is a release boundary and must be read afresh")
public abstract class VerifyPlayerTickArchitectureTask extends DefaultTask {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final String ACCESSOR =
            "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER =
            "Lorg/spongepowered/asm/mixin/gen/Invoker;";

    private static final List<String> CRITICAL_ENTRIES = List.of(
            "fabric.mod.json",
            "fabrolia.common.mixins.json",
            "com/axalotl/fabrolia/common/regions/AsyncIslandPlayerTicks.class",
            "com/axalotl/fabrolia/common/regions/AsyncIslandPlayerTicks$PlayerState.class",
            "com/axalotl/fabrolia/common/regions/AsyncIslandPlayerTicks$MainDisconnectScope.class",
            "com/axalotl/fabrolia/common/regions/AsyncIslandPlayerTicks$MainDisconnectOrigin.class",
            "com/axalotl/fabrolia/common/mixin/network/"
                    + "ServerCommonPacketListenerIslandDisconnectMixin.class",
            "com/axalotl/fabrolia/common/mixin/carpet/CarpetFakePlayerIslandMixin.class");

    private static final List<String> REMOVED_PLAYER_TICK_PREFIXES = List.of(
            "com/axalotl/fabrolia/common/regions/RegionalPlayerTicks",
            "com/axalotl/fabrolia/common/regions/PlayerOwnership",
            "com/axalotl/fabrolia/common/parallelised/utils/FabroliaListenerLane",
            "com/axalotl/fabrolia/common/mixin/server/ServerGamePacketRoutingMixin",
            "com/axalotl/fabrolia/common/mixin/network/PacketUtilsWorkerMixin",
            "com/axalotl/fabrolia/common/regions/PlayerTickActor",
            "com/axalotl/fabrolia/common/regions/PlayerTickPhase");

    private static final Pattern VERSION = Pattern.compile(
            "\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getArchiveFile();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getOwnedOutputs();

    @Input
    public abstract Property<String> getExpectedVersion();

    @TaskAction
    public void verifyArchive() {
        Path archive = getArchiveFile().get().getAsFile().toPath()
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(archive)) {
            throw new GradleException("Fabric jar task did not produce an archive: " + archive);
        }

        byte[] snapshot;
        try {
            snapshot = Files.readAllBytes(archive);
        } catch (IOException readFailure) {
            throw new GradleException("Unable to snapshot Fabric JAR " + archive, readFailure);
        }

        getLogger().lifecycle("Player-tick verifier archive: {}", archive);
        getLogger().lifecycle("Player-tick verifier SHA256: {}", digest(snapshot, "SHA-256"));
        getLogger().lifecycle("Player-tick verifier MD5: {}", digest(snapshot, "MD5"));

        List<String> failures = new ArrayList<>();
        Map<String, byte[]> packaged = readSnapshot(snapshot, failures);
        Map<String, byte[]> expected = readOwnedOutputs(failures);

        if (expected.isEmpty()) {
            failures.add("no tested common/fabric outputs were supplied");
        }
        for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
            byte[] actual = packaged.get(entry.getKey());
            if (actual == null) {
                failures.add("tested output is missing from Fabric JAR: " + entry.getKey());
            } else if (!Arrays.equals(entry.getValue(), actual)) {
                failures.add("Fabric JAR differs from tested output: " + entry.getKey());
            }
        }

        for (String critical : CRITICAL_ENTRIES) {
            if (!packaged.containsKey(critical)) {
                failures.add("critical player-disconnect entry is missing: " + critical);
            }
        }
        for (String removed : REMOVED_PLAYER_TICK_PREFIXES) {
            boolean present = packaged.keySet().stream().anyMatch(name ->
                    name.equals(removed + ".class") || name.startsWith(removed + "$"));
            if (present) {
                failures.add("removed player-tick implementation is packaged: " + removed);
            }
        }

        verifyVersion(packaged, failures);
        verifyMixinStaticVisibility(packaged, failures);

        if (!failures.isEmpty()) {
            throw new GradleException("Packaged player-tick verification failed:\n - "
                    + String.join("\n - ", failures));
        }
        getLogger().lifecycle(
                "Packaged player-tick verification passed: {} tested files are byte-identical",
                expected.size());
    }

    private Map<String, byte[]> readOwnedOutputs(List<String> failures) {
        Map<String, byte[]> expected = new LinkedHashMap<>();
        List<File> roots = getOwnedOutputs().getFiles().stream()
                .sorted(Comparator.comparing(File::getAbsolutePath))
                .toList();
        for (File rootFile : roots) {
            Path root = rootFile.toPath().toAbsolutePath().normalize();
            if (!Files.exists(root)) {
                failures.add("tested output root is missing: " + root);
                continue;
            }
            if (Files.isRegularFile(root)) {
                addExpected(expected, root.getFileName().toString(), root, failures);
                continue;
            }
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .sorted().toList()) {
                    String name = root.relativize(file).toString().replace(File.separatorChar, '/');
                    addExpected(expected, name, file, failures);
                }
            } catch (IOException walkFailure) {
                failures.add("cannot read tested output root " + root + ": "
                        + walkFailure.getMessage());
            }
        }
        return expected;
    }

    private static void addExpected(Map<String, byte[]> expected,
                                    String name,
                                    Path file,
                                    List<String> failures) {
        // Loom intentionally rewrites this descriptor while nesting dependencies.
        // Its release version is checked separately against the packaged JSON.
        if ("fabric.mod.json".equals(name)) {
            return;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException readFailure) {
            failures.add("cannot read tested output " + file + ": " + readFailure.getMessage());
            return;
        }
        byte[] previous = expected.putIfAbsent(name, bytes);
        if (previous != null && !Arrays.equals(previous, bytes)) {
            failures.add("common/fabric tested outputs disagree for " + name);
        }
    }

    private static Map<String, byte[]> readSnapshot(byte[] snapshot,
                                                     List<String> failures) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(snapshot))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] bytes = zip.readAllBytes();
                if (entries.putIfAbsent(entry.getName(), bytes) != null) {
                    failures.add("duplicate archive entry: " + entry.getName());
                }
            }
        } catch (IOException malformed) {
            throw new GradleException("Unable to inspect snapshotted Fabric JAR", malformed);
        }
        return entries;
    }

    private void verifyVersion(Map<String, byte[]> packaged, List<String> failures) {
        byte[] metadata = packaged.get("fabric.mod.json");
        if (metadata == null) {
            return;
        }
        Matcher matcher = VERSION.matcher(new String(metadata, java.nio.charset.StandardCharsets.UTF_8));
        if (!matcher.find()) {
            failures.add("fabric.mod.json has no string version");
        } else if (!getExpectedVersion().get().equals(matcher.group(1))) {
            failures.add("fabric.mod.json version is " + matcher.group(1)
                    + ", expected " + getExpectedVersion().get());
        }
    }

    private static void verifyMixinStaticVisibility(Map<String, byte[]> packaged,
                                                    List<String> failures) {
        for (Map.Entry<String, byte[]> entry : packaged.entrySet()) {
            if (!entry.getKey().endsWith(".class")) {
                continue;
            }
            ClassNode owner = new ClassNode(Opcodes.ASM9);
            try {
                new ClassReader(entry.getValue()).accept(owner,
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (RuntimeException malformed) {
                failures.add("malformed packaged class: " + entry.getKey());
                continue;
            }
            if (!hasAnnotation(owner.visibleAnnotations, MIXIN)
                    && !hasAnnotation(owner.invisibleAnnotations, MIXIN)) {
                continue;
            }
            for (MethodNode method : owner.methods) {
                if (!method.name.startsWith("<")
                        && (method.access & Opcodes.ACC_STATIC) != 0
                        && (method.access & Opcodes.ACC_PRIVATE) == 0
                        && (method.access & Opcodes.ACC_SYNTHETIC) == 0
                        && !hasAnnotation(method.visibleAnnotations, OVERWRITE)
                        && !hasAnnotation(method.visibleAnnotations, ACCESSOR)
                        && !hasAnnotation(method.visibleAnnotations, INVOKER)) {
                    failures.add("Mixin runtime would reject non-private static method: "
                            + owner.name.replace('/', '.') + "#" + method.name + method.desc);
                }
            }
        }
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations,
                                         String descriptor) {
        return annotations != null && annotations.stream()
                .anyMatch(annotation -> descriptor.equals(annotation.desc));
    }

    private static String digest(byte[] bytes, String algorithm) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Missing digest algorithm " + algorithm, impossible);
        }
    }
}
