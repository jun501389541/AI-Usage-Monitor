package com.aiusage.monitor.storage;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SQLite says "nothing was written" by returning -1, not by throwing.
 * Phase 7 review P2 found this once ({@code SqliteBridgeRepository} discarded it, so a
 * pairing looked recorded while the computer was never in the table) and the same shape was
 * then counted across the storage layer: nine more insert results thrown away.
 *
 * <p>These cannot be asserted behaviourally on the host JVM: {@code android.database.sqlite}
 * is a stub there, so no fake database returns -1 at a moment a test can arrange. What is
 * checkable is the source, and the check is written so that both directions fail:
 *
 * <ul>
 *   <li>an insert whose result is discarded is listed by file and line;</li>
 *   <li>if the scan ever finds no insert sites at all — wrong working directory, a moved
 *       package, a regex that stopped matching — that is reported as the guard being broken,
 *       not as the code being clean, because an empty scan would otherwise pass silently.</li>
 * </ul>
 *
 * <p>{@code insertOrThrow} is exempt by contract: it throws on the constraint failure it
 * exists to report. That exemption is pinned by counting it, so deleting the call is caught
 * rather than quietly shrinking the rule.
 */
public class StorageWriteReportTest {

    private static final String MAIN_SOURCE = "app/src/main/java";
    private static final String STORAGE_PACKAGE = "com/aiusage/monitor/storage/";

    /**
     * The ten writes the storage layer must report: nine rows that used to discard their
     * result plus the bridges row that started this. Raised deliberately when a new checked
     * insert is added, so a lowering here has to be an argument someone wrote down.
     */
    private static final int MIN_CHECKED_INSERTS = 10;
    private static final int EXPECTED_THROWING_INSERTS = 1;

    @Test
    public void everyInsertResultIsChecked() throws IOException {
        List<String> unchecked = new ArrayList<String>();
        List<String> checked = new ArrayList<String>();
        List<String> throwing = new ArrayList<String>();
        for (File file : sources()) {
            String path = pathOf(file);
            if (!path.contains(STORAGE_PACKAGE)) {
                continue;
            }
            String[] lines = read(file).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (!line.contains(".insert")) {
                    continue;
                }
                String where = path.substring(path.indexOf(STORAGE_PACKAGE)) + ":" + (i + 1);
                // String.matches() anchors at both ends, so every pattern here has to consume
                // the rest of the line too - a pattern ending at "\(" silently missed the three
                // calls whose arguments continue on the same line, and the count below is what
                // exposed that instead of an empty failure list.
                if (line.contains(".insertOrThrow(")) {
                    throwing.add(where);
                } else if (line.matches(".*\\blong\\s+\\w+\\s*=.*\\.insert(WithOnConflict)?\\s*\\(.*")) {
                    checked.add(where);
                } else if (line.matches(".*\\.insert(WithOnConflict)?\\s*\\(.*")) {
                    unchecked.add(where);
                }
            }
        }
        // The specific complaint first: a discarded write must be reported by file and line,
        // not as a count mismatch that makes the reader go hunting for which one moved.
        if (!unchecked.isEmpty()) {
            fail("these writes discard SQLite's -1 \"nothing was written\" instead of failing:"
                    + "\n  " + join(unchecked)
                    + "\nA discarded result means the caller reported a row it never stored.");
        }
        assertTrue("the scan found " + checked.size() + " checked and " + throwing.size()
                + " throwing inserts; expected at least " + MIN_CHECKED_INSERTS
                + " checked - an almost-empty scan means this guard itself broke, not that the"
                + " code is clean", checked.size() >= MIN_CHECKED_INSERTS);
        assertTrue("insertOrThrow moved: expected " + EXPECTED_THROWING_INSERTS + ", found "
                + throwing, throwing.size() == EXPECTED_THROWING_INSERTS);
    }

    private static String join(List<String> items) {
        StringBuilder out = new StringBuilder();
        for (String item : items) {
            if (out.length() > 0) {
                out.append("\n  ");
            }
            out.append(item);
        }
        return out.toString();
    }

    private static List<File> sources() {
        List<File> files = new ArrayList<File>();
        collect(root(), files);
        return files;
    }

    private static void collect(File directory, List<File> into) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, into);
            } else if (child.getName().endsWith(".java")) {
                into.add(child);
            }
        }
    }

    private static File root() {
        String userDir = System.getProperty("user.dir");
        File fromRepo = new File(userDir, MAIN_SOURCE);
        if (fromRepo.isDirectory()) {
            return fromRepo;
        }
        File fromModule = new File(userDir, "src/main/java");
        if (fromModule.isDirectory()) {
            return fromModule;
        }
        fail("cannot find " + MAIN_SOURCE + " from " + userDir
                + " - this guard would be scanning nothing");
        return null;
    }

    private static String pathOf(File file) {
        return file.getPath().replace('\\', '/');
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }
}
