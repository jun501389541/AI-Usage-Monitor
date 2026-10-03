package com.aiusage.monitor.usage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A storage API nothing in the app calls is a liability, not a feature.
 *
 * <p>Phase 3's review removed seven members that only tests still exercised
 * ({@code docs/REVIEW-AND-NEXT-STEPS.md} R17). The uncomfortable part is that
 * {@code UsageRepository.history()} had been in that state since Phase 1: an
 * accessor whose existence was proved by the tests written against it, and by
 * nothing else. A mirror-image fake then made those tests green, so the suite
 * was measuring its own copy.
 *
 * <p>This test closes that hole for the usage interface: every method declared
 * on {@link UsageRepository} must be called somewhere under
 * {@code app/src/main/java} other than its own declaration and its SQLite
 * implementation. It deliberately fails when it cannot find the source tree
 * rather than passing on an empty scan -- a guard that silently scans nothing is
 * the same failure mode it exists to catch.
 */
public class UsageRepositoryContractTest {

    private static final Pattern METHOD = Pattern.compile(
            "^\\s{4}(?:public\\s+)?[A-Za-z][\\w<>,\\.\\[\\]\\s]*?\\s(\\w+)\\s*\\(", Pattern.MULTILINE);

    @Test
    public void everyDeclaredMethodIsCalledByProductionCode() throws IOException {
        String source = UsageRepository.class.getName().replace('.', '/') + ".java";
        Path interfaceFile = mainSourceRoot().resolve(source);
        List<String> declared = declaredMethods(interfaceFile);

        assertFalse("no methods parsed from " + interfaceFile + "; the regex has drifted "
                + "from the file it is meant to read", declared.isEmpty());
        assertTrue("expected at least the five methods the interface has, found "
                + declared.size(), declared.size() >= 5);

        List<Path> production = productionSources();
        assertTrue("only " + production.size() + " production sources found; the scan path "
                + "is wrong and this test would pass vacuously", production.size() > 40);

        List<String> uncalled = new ArrayList<>();
        for (String method : declared) {
            if (!calledIn(method, production, interfaceFile)) {
                uncalled.add(method);
            }
        }
        assertTrue("these UsageRepository methods have no production caller: " + uncalled
                + " -- either wire one up or delete the accessor (review finding R17)",
                uncalled.isEmpty());
    }

    /**
     * The module's main source root, resolved from the test working directory.
     *
     * <p>Gradle runs unit tests with the module directory as the working
     * directory, so {@code src/main/java} is the tree under audit. If that ever
     * changes the call below throws rather than scanning nothing.
     */
    private static Path mainSourceRoot() {
        Path candidate = Paths.get("src", "main", "java").toAbsolutePath();
        assertTrue("cannot find the production sources at " + candidate
                + "; this test must not pass by scanning nothing", Files.isDirectory(candidate));
        return candidate;
    }

    private static List<String> declaredMethods(Path interfaceFile) throws IOException {
        String text = new String(Files.readAllBytes(interfaceFile), StandardCharsets.UTF_8);
        List<String> names = new ArrayList<>();
        Matcher matcher = METHOD.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static List<Path> productionSources() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(mainSourceRoot())) {
            walk.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .forEach(files::add);
        }
        return files;
    }

    private static boolean calledIn(String method, List<Path> files, Path interfaceFile)
            throws IOException {
        // A use is the name followed by "(" -- qualified as
        // {@code repository.method(}, bare as {@code method(}, or as a method
        // reference {@code repository::method} -- on a line that is not the
        // method's own declaration or a comment mentioning it. Anything else is
        // a consumer.
        Pattern use = Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(|::\\s*"
                + Pattern.quote(method) + "\\b");
        for (Path file : files) {
            if (file.equals(interfaceFile)) {
                continue;
            }
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Matcher matcher = use.matcher(text);
            while (matcher.find()) {
                String line = lineOf(text, matcher.start());
                if (line == null) {
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.startsWith("public") || trimmed.startsWith("private")
                        || trimmed.startsWith("protected") || trimmed.startsWith("@Override")
                        || trimmed.startsWith("*") || trimmed.startsWith("//")) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private static String lineOf(String text, int index) {
        int start = text.lastIndexOf('\n', index);
        int end = text.indexOf('\n', index);
        if (start < 0 || end < 0) {
            return null;
        }
        return text.substring(start + 1, end);
    }
}
