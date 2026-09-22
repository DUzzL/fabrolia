package com.axalotl.fabrolia.common.mixin;

import com.axalotl.fabrolia.common.mixin.world.ServerLevelMixin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces MixinApplicatorStandard.checkMethodVisibility for every compiled
 * common mixin. Javac and Loom's compile-time mixin checks do not enforce this
 * rule; Mixin otherwise reports it only when the target class is first loaded.
 */
final class MixinStaticMethodVisibilityTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final String ACCESSOR =
            "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER =
            "Lorg/spongepowered/asm/mixin/gen/Invoker;";

    @Test
    void everyStaticMixinMethodCanPassTheRuntimeApplicatorVisibilityCheck()
            throws IOException, URISyntaxException {
        List<String> violations = new ArrayList<>();
        String archive = System.getenv("FABROLIA_MIXIN_ARCHIVE");

        if (archive == null || archive.isBlank()) {
            Path classes = Path.of(ServerLevelMixin.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path mixinRoot = classes.resolve("com/axalotl/fabrolia/common/mixin");
            try (var files = Files.walk(mixinRoot)) {
                for (Path file : files
                        .filter(path -> path.toString().endsWith(".class"))
                        .sorted(Comparator.naturalOrder()).toList()) {
                    inspect(Files.readAllBytes(file), violations);
                }
            }
        } else {
            try (ZipFile zip = new ZipFile(archive)) {
                var entries = zip.stream()
                        .filter(entry -> !entry.isDirectory()
                                && entry.getName().endsWith(".class"))
                        .sorted(Comparator.comparing(entry -> entry.getName()))
                        .toList();
                for (var entry : entries) {
                    try (var input = zip.getInputStream(entry)) {
                        inspect(input.readAllBytes(), violations);
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(), () ->
                "Mixin runtime would reject non-private static methods:\n"
                        + String.join("\n", violations));
    }

    private static void inspect(byte[] bytecode, List<String> violations) {
        ClassNode owner = new ClassNode();
        new ClassReader(bytecode).accept(
                owner, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!hasAnnotation(owner.visibleAnnotations, MIXIN)
                && !hasAnnotation(owner.invisibleAnnotations, MIXIN)) {
            return;
        }
        for (MethodNode method : owner.methods) {
            if (!method.name.startsWith("<")
                    && (method.access & Opcodes.ACC_STATIC) != 0
                    && (method.access & Opcodes.ACC_PRIVATE) == 0
                    && (method.access & Opcodes.ACC_SYNTHETIC) == 0
                    && !hasAnnotation(method.visibleAnnotations, OVERWRITE)
                    // Accessors/invokers are removed from the normal-method
                    // pass and generated later by applyAccessors().
                    && !hasAnnotation(method.visibleAnnotations, ACCESSOR)
                    && !hasAnnotation(method.visibleAnnotations, INVOKER)) {
                violations.add(owner.name.replace('/', '.') + '#'
                        + method.name + method.desc);
            }
        }
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations,
                                         String descriptor) {
        return annotations != null
                && annotations.stream().anyMatch(annotation -> descriptor.equals(annotation.desc));
    }
}
