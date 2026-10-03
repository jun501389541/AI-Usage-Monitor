package com.aiusage.monitor.account;

import com.aiusage.monitor.model.Account;

import java.util.List;

/**
 * Persistence for accounts. Spec §45 ({@code account/AccountRepository}).
 *
 * <p>Deliberately separate from {@code AccountManager}: this interface is pure
 * storage, while the manager adds the rules (which accounts are refreshable,
 * how ordering is maintained, what happens when one is deleted). Keeping them
 * apart means the manager is testable against an in-memory implementation.
 */
public interface AccountRepository {

    /** All accounts, ordered by {@code sortOrder} then creation time. */
    List<Account> findAll();

    /** Only enabled accounts, in the same order. */
    List<Account> findEnabled();

    /** One account, or null. */
    Account findById(String id);

    /** Inserts or replaces an account. */
    void save(Account account);

    /** Removes an account. History is removed separately by the caller. */
    void delete(String id);

    /** Persists a new ordering, given account ids in the desired order. */
    void reorder(List<String> orderedIds);

    /** Number of accounts, used to seed the first one's name. */
    int count();
}
