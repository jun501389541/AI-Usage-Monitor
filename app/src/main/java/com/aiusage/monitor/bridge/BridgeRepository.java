package com.aiusage.monitor.bridge;

import com.aiusage.monitor.model.Bridge;

import java.util.List;

/**
 * Where the paired computers are kept. Spec §21, and the interface Phase 6's
 * decision A3 anticipated: the address used to live inside the credential payload,
 * so a laptop changing networks meant rewriting an encrypted secret to change a
 * string that is not a secret.
 *
 * <p>An account with no {@code bridge_id} is the hand-typed debug path from Phase 6
 * and keeps working unchanged — this repository is additive, and every reader has to
 * say what it does in that case rather than assume a Bridge row exists.
 */
public interface BridgeRepository {

    /** All paired bridges, oldest pairing first. */
    List<Bridge> findAll();

    /** The bridge with this Bridge-generated id, or null. */
    Bridge findById(String id);

    /** Inserts or replaces by {@link Bridge#getId()}. */
    void save(Bridge bridge);

    /**
     * Points an existing bridge at a new address after the phone reached it there.
     * The id and the fingerprint do not move: that is the whole content of "the
     * same computer, different network".
     */
    void updateBaseUrl(String id, String baseUrl);

    /** Records that a request to this bridge succeeded. */
    void touchLastSeen(String id, long atMs);

    /**
     * Forgets a bridge. The caller's contract is that accounts still pointing at it
     * are dealt with first — deleting the row under a live account is how an
     * account ends up with nowhere to query and no message that says so.
     */
    void delete(String id);
}
