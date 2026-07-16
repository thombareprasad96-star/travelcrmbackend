package com.crm.travelcrm.lead.assignment.strategy;

import java.util.List;

/**
 * Stable, restart-safe round-robin pick. Given the candidate ids in ascending order and the last id
 * the cursor handed out, returns the next candidate — the smallest id strictly greater than the
 * cursor, wrapping to the smallest candidate when the cursor is at/after the end (or unset).
 *
 * <p>Because the pick is a pure function of {@code (sortedCandidateIds, lastAssignedUserId)} and the
 * cursor is persisted, the rotation survives a server restart and never depends on wall-clock time or
 * randomness. Shared by {@link RoundRobinAssignmentStrategy} and by
 * {@link LoadBasedAssignmentStrategy}'s tie-break.
 */
final class RoundRobinRotation {

    private RoundRobinRotation() {}

    /**
     * @param sortedCandidateIds candidate ids in ascending order (must already be sorted; may be empty)
     * @param lastAssignedUserId the cursor's last value, or {@code null} if it has never advanced
     * @return the next candidate id, or {@code null} if there are no candidates
     */
    static Long pickNext(List<Long> sortedCandidateIds, Long lastAssignedUserId) {
        if (sortedCandidateIds == null || sortedCandidateIds.isEmpty()) {
            return null;
        }
        if (lastAssignedUserId != null) {
            for (Long id : sortedCandidateIds) {
                if (id > lastAssignedUserId) {
                    return id;
                }
            }
        }
        // Cursor unset, or past the last candidate → wrap to the start.
        return sortedCandidateIds.get(0);
    }
}