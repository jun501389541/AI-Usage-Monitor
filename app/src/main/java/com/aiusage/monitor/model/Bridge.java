package com.aiusage.monitor.model;

/**
 * One paired computer: what it is called, where it is now, and what its
 * certificate must hash to. Spec §21/§22.
 *
 * <p>{@link #getId()} is the Bridge's own random identifier — the same string that
 * is in the subject of its self-signed certificate — and never an address. That
 * distinction is the point of the row: an address changes when a laptop joins
 * another network, and Spec §54 forbids letting such a change mean "a different
 * computer" (or, worse, "the same computer, so trust whatever answers here").
 *
 * <p>{@link #getFingerprint()} is what makes {@link #getBaseUrl()} re-editable
 * without opening a door. The phone pins the digest it saw at pairing time and
 * compares it against the certificate on every later connection; an address that
 * now belongs to somebody else's machine fails that comparison rather than
 * silently handing over a device token.
 *
 * <p>Immutable, like {@link Account}: rows are replaced rather than mutated, so a
 * refresh holding one object cannot observe it changing underneath.
 */
public final class Bridge {

    private final String id;
    private final String name;
    private final String baseUrl;
    private final String fingerprint;
    private final long addedAt;
    private final long lastSeen;

    public Bridge(String id, String name, String baseUrl, String fingerprint,
                  long addedAt, long lastSeen) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.fingerprint = fingerprint == null ? "" : fingerprint;
        this.addedAt = addedAt;
        this.lastSeen = lastSeen;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** e.g. {@code https://192.168.1.20:38411}; no trailing slash. */
    public String getBaseUrl() {
        return baseUrl;
    }

    /** Hex SHA-256 of the certificate's SubjectPublicKeyInfo. */
    public String getFingerprint() {
        return fingerprint;
    }

    public long getAddedAt() {
        return addedAt;
    }

    /** When a request to this Bridge last succeeded; 0 until it has. */
    public long getLastSeen() {
        return lastSeen;
    }

    public Builder toBuilder() {
        return new Builder()
                .id(id)
                .name(name)
                .baseUrl(baseUrl)
                .fingerprint(fingerprint)
                .addedAt(addedAt)
                .lastSeen(lastSeen);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A copy pointing at a new address: what "the laptop changed networks" means. */
    public Bridge withBaseUrl(String value) {
        return toBuilder().baseUrl(value).build();
    }

    /** A copy recording that the Bridge answered. */
    public Bridge withLastSeen(long atMs) {
        return toBuilder().lastSeen(atMs).build();
    }

    @Override
    public String toString() {
        return "Bridge{id='" + id + "', name='" + name + "', baseUrl='" + baseUrl
                + "', fingerprint='" + fingerprint + "', addedAt=" + addedAt
                + ", lastSeen=" + lastSeen + '}';
    }

    public static final class Builder {
        private String id = "";
        private String name = "";
        private String baseUrl = "";
        private String fingerprint = "";
        private long addedAt;
        private long lastSeen;

        public Builder id(String value) {
            this.id = value == null ? "" : value;
            return this;
        }

        public Builder name(String value) {
            this.name = value == null ? "" : value;
            return this;
        }

        public Builder baseUrl(String value) {
            this.baseUrl = value == null ? "" : value;
            return this;
        }

        public Builder fingerprint(String value) {
            this.fingerprint = value == null ? "" : value;
            return this;
        }

        public Builder addedAt(long value) {
            this.addedAt = value;
            return this;
        }

        public Builder lastSeen(long value) {
            this.lastSeen = value;
            return this;
        }

        public Bridge build() {
            return new Bridge(id, name, baseUrl, fingerprint, addedAt, lastSeen);
        }
    }
}
