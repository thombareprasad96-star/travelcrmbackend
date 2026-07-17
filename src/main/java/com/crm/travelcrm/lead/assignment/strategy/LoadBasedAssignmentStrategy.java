package com.crm.travelcrm.lead.assignment.strategy;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Load-based assignment: recommend the eligible user with the <b>lowest workload score</b>. When
 * several users are tied on the lowest score, the tie is broken by round-robin <b>among only the
 * tied users</b> (so the rotation is fair and never oscillates between the same two people while a
 * third with equal load is skipped). Purely a function of the scores + cursor supplied in the
 * context; the scores are already zero-filled by the caller, so an idle user is a genuine candidate
 * with score 0.
 *
 * <p>The score is {@code UserWorkload.score()} — {@code todo + inProgress + activeLeads +
 * openReminders} — computed by {@code WorkloadService}. This class does not know or care what it is
 * made of; that is the point of it being handed in.
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

        // Lowest workload score across all candidates (default 0 for a user absent from the map).
        long lowest = candidates.stream()
                .mapToLong(id -> ctx.getWorkloadScores().getOrDefault(id, 0L))
                .min()
                .orElse(0L);

        // The tied subset, preserving the ascending order the candidate list already carries.
        List<Long> tied = candidates.stream()
                .filter(id -> ctx.getWorkloadScores().getOrDefault(id, 0L) == lowest)
                .toList();

        // Single clear winner → take it; otherwise round-robin within the tie.
        if (tied.size() == 1) {
            return Optional.of(tied.get(0));
        }
        return Optional.ofNullable(
                RoundRobinRotation.pickNext(tied, ctx.getLastAssignedUserId()));
    }
}