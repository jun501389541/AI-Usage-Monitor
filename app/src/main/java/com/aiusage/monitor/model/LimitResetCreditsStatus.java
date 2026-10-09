package com.aiusage.monitor.model;

/** Why the latest Codex Bridge response did or did not include reset credits. */
public enum LimitResetCreditsStatus {
    AVAILABLE,
    NOT_RETURNED,
    INVALID_FORMAT,
    LEGACY_BRIDGE
}
