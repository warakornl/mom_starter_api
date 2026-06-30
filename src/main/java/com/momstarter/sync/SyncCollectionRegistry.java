package com.momstarter.sync;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registry of push-accepted {@link SyncCollection} implementations.
 *
 * <p>Spring auto-discovers all {@code @Component} {@link SyncCollection} beans and injects
 * them here. Adding a new collection = implementing {@link SyncCollection}, annotating with
 * {@code @Component} — no registry edits required.
 *
 * <p>Collections that are <strong>NOT push-accepted</strong> (e.g. {@code pregnancyProfile},
 * {@code account}) must NOT implement this interface. They generate {@code rejected[]}
 * entries with {@code code: unknown_collection} when a client mistakenly includes them in
 * {@code changes} (api-contract §8).
 *
 * <p>The fixed pull order for cursor pagination is determined by {@link #pullOrder()}.
 */
@Component
public class SyncCollectionRegistry {

    private final Map<String, SyncCollection> byName;

    /**
     * Fixed ordered list of pull-eligible collection names. The order governs cold-start cursor
     * pagination: collections are scanned in this sequence; the cursor encodes which collection
     * the drain is currently in (sync spec §B.4).
     *
     * <p>NOTE: this does NOT include {@code pregnancyProfile} — that is pull-replicated but
     * NOT managed as a {@link SyncCollection} (it is hardcoded in the pull path). It is
     * appended last in the pull response by {@code SyncService}.
     */
    static final List<String> PULL_ORDER = List.of(
            "supplyItems",
            "reminders",
            "reminderOccurrences",
            "checklistItems",
            "kickCountSessions");

    public SyncCollectionRegistry(List<SyncCollection> collections) {
        this.byName = collections.stream()
                .collect(Collectors.toMap(SyncCollection::name, Function.identity()));
    }

    /** Returns the collection for the given name, or empty if not registered. */
    public Optional<SyncCollection> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** All registered collections in {@link #PULL_ORDER} (used for pull cursor iteration). */
    public List<SyncCollection> inPullOrder() {
        return PULL_ORDER.stream()
                .map(byName::get)
                .filter(c -> c != null)
                .toList();
    }
}
