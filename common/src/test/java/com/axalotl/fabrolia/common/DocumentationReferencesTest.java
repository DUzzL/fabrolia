package com.axalotl.fabrolia.common;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The code map is how people and agents find code ("where to look when"). A
 * renamed or deleted class or test must not leave it pointing at nothing.
 */
class DocumentationReferencesTest {

    private static final Pattern TEST_REF = Pattern.compile("`(?:T/[\\w/]+/)?(\\w+Test)`");
    /** A file name, optionally with {@code *} wildcards ({@code Island*GuardMixin.java}). */
    private static final Pattern JAVA_REF = Pattern.compile("(?<![\\w*])([\\w*]+)\\.java\\b");

    @Test
    void codeMapAndAgentGuideNameOnlyExistingClassesAndTests() throws IOException {
        Path root = repositoryRoot();
        Assumptions.assumeTrue(root != null, "repository tree not available");
        Set<String> sources = classNames(root, "src/main/java", "src/main/groovy");
        Set<String> tests = classNames(root, "src/test/java");

        Set<String> missing = new TreeSet<>();
        for (String doc : new String[]{"docs/CODEMAP.md", "AGENTS.md"}) {
            String text = Files.readString(root.resolve(doc));
            Matcher test = TEST_REF.matcher(text);
            while (test.find()) {
                if (!tests.contains(test.group(1))) missing.add(doc + ": " + test.group(1));
            }
            Matcher java = JAVA_REF.matcher(text);
            while (java.find()) {
                String name = java.group(1);
                if (!exists(name, sources) && !exists(name, tests)) missing.add(doc + ": " + name + ".java");
            }
        }
        assertTrue(missing.isEmpty(), "documentation references missing code: " + missing);
    }

    private static boolean exists(String name, Set<String> names) {
        if (!name.contains("*")) return names.contains(name);
        Pattern glob = Pattern.compile(name.replace("*", "\\w*"));
        return names.stream().anyMatch(n -> glob.matcher(n).matches());
    }

    private static Set<String> classNames(Path root, String... sourceDirs) throws IOException {
        Set<String> names = new TreeSet<>();
        for (String module : new String[]{"api", "common", "fabric", "neoforge", "buildSrc"}) {
            for (String dir : sourceDirs) {
                Path base = root.resolve(module).resolve(dir);
                if (!Files.isDirectory(base)) continue;
                try (Stream<Path> files = Files.walk(base)) {
                    names.addAll(files.map(p -> p.getFileName().toString())
                            .filter(n -> n.endsWith(".java"))
                            .map(n -> n.substring(0, n.length() - ".java".length()))
                            .collect(Collectors.toSet()));
                }
            }
        }
        return names;
    }

    private static Path repositoryRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("docs/CODEMAP.md"))
                    && Files.isRegularFile(dir.resolve("AGENTS.md"))) {
                return dir;
            }
        }
        return null;
    }
}
