package com.crm.travelcrm.lead.assignment.strategy;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Load-based assignment: recommend the eligible user with the <b>fewest active leads</b>. When
 * several users are tied on the lowest count, the tie is broken by round-robin <b>among only the
 * tied users</b> (so the rotation is fair and never oscillates between the same two people while a
 * third with equal load is skipped). Purely a function of the counts + cursor supplied in the
 * context; the counts are already zero-filled by the caller, so a user with no active leads is a
 * genuine candidate with count 0.
 */
@Component
public class LoadBasedAssignmentStrategy implements LeadAssignmentStrategy {

    @Override
    public AssignmentStrategyType type() {
        return AssignmentStrategyType.LOAD_BASED;
    }

    @Override
    public Optional<Long> recommend(AssignmentContext ctx) {
        List<Long> candidates = ctx.getCandidateUserIds();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        // Lowest active-lead count across all candidates (default 0 for a user absent from the map).
        long lowest = candidates.stream()
                .mapToLong(id -> ctx.getActiveLeadCounts().getOrDefault(id, 0L))
                .min()
                .orElse(0L);

        // The tied subset, preserving the ascending order the candidate list already carries.
        List<Long> tied = candidates.stream()
                .filter(id -> ctx.getActiveLeadCounts().getOrDefault(id, 0L) == lowest)
                .toList();

        // Single clear winner → take it; otherwise round-robin within the tie.
        if (tied.size() == 1) {
            return Optional.of(tied.get(0));
        }
        return Optional.ofNullable(
                RoundRobinRotation.pickNext(tied, ctx.getLastAssignedUserId()));
    }
}