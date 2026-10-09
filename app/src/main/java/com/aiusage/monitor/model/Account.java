package com.aiusage.monitor.model;

import com.aiusage.monitor.auth.AuthType;

/**
 * One configured account: a provider plus the way it authenticates. Spec §6.
 *
 * <p>An account is what the user sees and names ("DeepSeek 个人"), and it is the
 * unit that widgets bind to. It never holds a secret — only a
 * {@code credentialId} pointing into the credential store. Spec §53 rule 11.
 *
 * <p>Field list is exactly the one the spec prescribes: {@code id / providerId /
 * displayName / authType / credentialId / bridgeId / enabled / pinned / sortOrder /
 * createdAt / updatedAt}.
 */
public final class Account {

    private final String id;
    private final String providerId;
    private final String displayName;
    private final AuthType authType;
    private final String credentialId;
    private final String bridgeId;
    private final boolean enabled;
    private final boolean pinned;
    private final int sortOrder;
    private final long createdAt;
    private final long updatedAt;

    private Account(Builder builder) {
        this.id = builder.id;
        this.providerId = builder.providerId;
        this.displayName = builder.displayName;
        this.authType = builder.authType;
        this.credentialId = builder.credentialId;
        this.bridgeId = builder.bridgeId;
        this.enabled = builder.enabled;
        this.pinned = builder.pinned;
        this.sortOrder = builder.sortOrder;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
    }

    /** Stable identifier. Survives a credential change. Spec §14. */
    public String getId() {
        return id;
    }

    /** For example {@code deepseek}. */
    public String getProviderId() {
        return providerId;
    }

    /** User-visible name, for example "DeepSeek 个人". */
    public String getDisplayName() {
        return displayName;
    }

    public AuthType getAuthType() {
        return authType;
    }

    /** Points at a row in the credential store; may be empty. */
    public String getCredentialId() {
        return credentialId;
    }

    /** Set only for bridge-backed accounts; may be empty. */
    public String getBridgeId() {
        return bridgeId;
    }

    /** Disabled accounts keep their history but are not refreshed. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Pinned accounts are grouped before ordinary accounts in the list. */
    public boolean isPinned() { return pinned; }

    /** Ordering hint for the account list. */
    public int getSortOrder() {
        return sortOrder;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public Builder toBuilder() {
        return new Builder()
                .id(id)
                .providerId(providerId)
                .displayName(displayName)
                .authType(authType)
                .credentialId(credentialId)
                .bridgeId(bridgeId)
                .enabled(enabled)
                .pinned(pinned)
                .sortOrder(sortOrder)
                .createdAt(createdAt)
                .updatedAt(updatedAt);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "Account{id=" + id + ", provider=" + providerId + ", name=" + displayName + "}";
    }

    public static final class Builder {

        private String id = "";
        private String providerId = "";
        private String displayName = "";
        private AuthType authType = AuthType.API_KEY;
        private String credentialId = "";
        private String bridgeId = "";
        private boolean enabled = true;
        private boolean pinned;
        private int sortOrder;
        private long createdAt = System.currentTimeMillis();
        private long updatedAt = System.currentTimeMillis();

        public Builder id(String value) {
            this.id = value == null ? "" : value;
            return this;
        }

        public Builder providerId(String value) {
            this.providerId = value == null ? "" : value;
            return this;
        }

        public Builder displayName(String value) {
            this.displayName = value == null ? "" : value;
            return this;
        }

        public Builder authType(AuthType value) {
            this.authType = value == null ? AuthType.CUSTOM : value;
            return this;
        }

        public Builder credentialId(String value) {
            this.credentialId = value == null ? "" : value;
            return this;
        }

        public Builder bridgeId(String value) {
            this.bridgeId = value == null ? "" : value;
            return this;
        }

        public Builder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        public Builder pinned(boolean value) {
            this.pinned = value;
            return this;
        }

        public Builder sortOrder(int value) {
            this.sortOrder = value;
            return this;
        }

        public Builder createdAt(long value) {
            this.createdAt = value;
            return this;
        }

        public Builder updatedAt(long value) {
            this.updatedAt = value;
            return this;
        }

        public Account build() {
            return new Account(this);
        }
    }
}
