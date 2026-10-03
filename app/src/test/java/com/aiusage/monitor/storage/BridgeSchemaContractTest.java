package com.aiusage.monitor.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aiusage.monitor.model.Bridge;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The shape of schema version 3, and the decisions baked into it. Phase 7 step 5.
 *
 * <p>{@code Database} extends {@code SQLiteOpenHelper}, so a host JVM can read its
 * constants but cannot open a database — which is also why the migration itself is
 * rehearsed on a device (the Phase 3 precedent: {@code assert-widget-slots.ps1}
 * downgrades and restores a copy of the real file). What is asserted here is the
 * part that a device run cannot distinguish: that the fresh and upgraded schemas are
 * the <em>same</em> DDL, that the upgrade is transactional, and that the migration
 * refuses to invent rows the user never paired.
 */
public class BridgeSchemaContractTest {

    // ------------------------------------------------------------ the model

    @Test
    public void aBridgeRowKeepsItsIdentityWhenItsAddressMoves() {
        Bridge original = Bridge.builder()
                .id("br_v7vv8u3i2tl0tjtq8j70")
                .name("jun-desktop")
                .baseUrl("https://192.168.1.20:38411")
                .fingerprint("0f39ce7fe1e562c039a10a1f93ff37da")
                .addedAt(100L)
                .lastSeen(200L)
                .build();

        Bridge moved = original.withBaseUrl("https://10.5.0.9:38411");

        assertEquals("https://10.5.0.9:38411", moved.getBaseUrl());
        assertEquals("the id is the identity, and it does not follow the address",
                original.getId(), moved.getId());
        assertEquals("nor may the pinned digest change because the network did",
                original.getFingerprint(), moved.getFingerprint());
        assertEquals(100L, moved.getAddedAt());
        assertEquals(200L, moved.getLastSeen());
    }

    @Test
    public void missingFieldsReadAsEmptyRatherThanNull() {
        Bridge blank = new Bridge(null, null, null, null, 0L, 0L);

        assertEquals("", blank.getId());
        assertEquals("", blank.getName());
        assertEquals("", blank.getBaseUrl());
        assertEquals("", blank.getFingerprint());
    }

    // ------------------------------------------------------- the schema pins

    @Test
    public void versionThreeAddsTheBridgesTableToBothPaths() throws IOException {
        String source = readDatabaseSource();

        assertTrue("the helper version has to move or onUpgrade never runs",
                source.contains("private static final int VERSION = 3;"));
        assertTrue("onCreate must build the table from the shared DDL",
                source.contains("db.execSQL(createBridgesTable(false));"));
        assertTrue("and so must the migration",
                source.contains("db.execSQL(createBridgesTable(true));"));
        assertEquals("exactly one definition of the bridges DDL, or the two schemas "
                        + "drift and a device cannot tell which one it has",
                1, count(source, "private static String createBridgesTable("));
        assertTrue("the upgrade has to be reachable from a version 2 database",
                source.contains("if (oldVersion < 3) {\n            migrateBridges(db);\n        }"));
    }

    @Test
    public void theMigrationCreatesNoRowsAndRunsInOneTransaction() throws IOException {
        String source = readDatabaseSource();

        int start = source.indexOf("private void migrateBridges(SQLiteDatabase db) {");
        int end = source.indexOf("private static String createBridgesTable(", start);
        assertTrue("migrateBridges must exist", start > 0);
        String body = source.substring(start, end);
        assertTrue("one transaction, because a half-applied migration never runs again",
                body.contains("db.beginTransaction()") && body.contains("db.setTransactionSuccessful()")
                        && body.contains("db.endTransaction()"));
        assertTrue("hand-typed accounts keep working with bridge_id = '' — inventing a "
                        + "bridge row for each would guess an identity only a pairing "
                        + "can answer",
                !body.contains("INSERT"));
    }

    @Test
    public void theBridgesColumnsAreWhatPairingNeedsToStore() throws IOException {
        String source = readDatabaseSource();
        int start = source.indexOf("private static String createBridgesTable(");
        String ddl = source.substring(start, source.indexOf("\n    }", start));

        for (String column : new String[]{"id TEXT PRIMARY KEY NOT NULL",
                "name TEXT NOT NULL", "base_url TEXT NOT NULL", "fingerprint TEXT NOT NULL",
                "added_at INTEGER NOT NULL", "last_seen INTEGER NOT NULL DEFAULT 0"}) {
            assertTrue("missing column: " + column + "\n" + ddl, ddl.contains(column));
        }
        assertTrue("accounts keep the pointer, not a copy of the address",
                source.contains("+ \"bridge_id TEXT NOT NULL DEFAULT '',\""));
    }

    private static String readDatabaseSource() throws IOException {
        String path = "app/src/main/java/com/aiusage/monitor/storage/Database.java";
        File fromRoot = new File(System.getProperty("user.dir"), path);
        File fromModule = new File(System.getProperty("user.dir"), path.substring("app/".length()));
        File candidate = fromRoot.isFile() ? fromRoot : fromModule;
        if (!candidate.isFile()) {
            fail("cannot find " + path + " from " + System.getProperty("user.dir")
                    + " - this guard would be scanning nothing");
        }
        String text = new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        assertTrue("wrong file scanned: " + candidate, text.contains("class Database"));
        return text;
    }

    private static int count(String haystack, String needle) {
        int found = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
    }
}
