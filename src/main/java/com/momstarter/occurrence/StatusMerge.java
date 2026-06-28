package com.momstarter.occurrence;

import java.time.Instant;
import java.util.Set;

/**
 * M1 status-merge precedence: a handled (done/snoozed) occurrence must NEVER
 * revert to a derived 'missed', regardless of timestamp. Otherwise last-write-wins
 * on the server-assigned updatedAt.
 */
public final class StatusMerge {

    private static final Set<String> HANDLED = Set.of("done", "snoozed");

    private StatusMerge() {}

    public static String merge(String existing, Instant existingUpdatedAt,
                               String incoming, Instant incomingUpdatedAt) {
        // M1 precedence overrides LWW in both directions.
        if (HANDLED.contains(existing) && "missed".equals(incoming)) return existing;
        if (HANDLED.contains(incoming) && "missed".equals(existing)) return incoming;

        // Otherwise plain last-write-wins on server updatedAt.
        if (incomingUpdatedAt.isAfter(existingUpdatedAt)) return incoming;
        if (incomingUpdatedAt.isBefore(existingUpdatedAt)) return existing;
        return incoming; // exact tie -> caller refines by version then clientId
    }
}
