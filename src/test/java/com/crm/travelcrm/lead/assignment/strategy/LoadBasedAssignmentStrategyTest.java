package com.crm.travelcrm.lead.assignment.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Lowest workload first; round-robin among ties" — the rule inbound leads are distributed by.
 *
 * <p>The strategy is a pure function of the context, so these are plain unit tests with no Spring and
 * no database.
 */
class LoadBasedAssignmentStrategyTest {

    private final LoadBasedAssignmentStrategy strategy = new LoadBasedAssignmentStrategy();

    private static AssignmentContext ctx(List<Long> candidates, Map<Long, Long> scores, Long cursor) {
        return AssignmentContext.builder()
                .tenantId(1L)
                .candidateUserIds(candidates)
                .workloadScores(scores)
                .lastAssignedUserId(cursor)
                .build();
    }

    @Nested
    @DisplayName("workload decides")
    class WorkloadFirst {

        @Test
        void picksTheLowestScoreEvenWhenTheCursorPointsElsewhere() {
            // Cursor says "10 went last, so 20 is next" — but 30 is idle. Workload wins; the cursor
            // only ever breaks ties.
            var pick = strategy.recommend(
                    ctx(List.of(10L, 20L, 30L), Map.of(10L, 5L, 20L, 4L, 30L, 0L), 10L));

            assertThat(pick).contains(30L);
        }

        @Test
        void aUserAbsentFromTheScoreMapCountsAsIdle() {
            // Zero-filling is the caller's job, but a missing entry must not make someone invisible —
            // it must make them the MOST attractive, or the emptiest person is never assigned.
            var pick = strategy.recommend(ctx(List.of(10L, 20L), Map.of(10L, 3L), null));

            assertThat(pick).contains(20L);
        }

        @Test
        void aSingleCandidateIsAlwaysThePick() {
            assertThat(strategy.recommend(ctx(List.of(7L), Map.of(7L, 99L), null))).contains(7L);
        }
    }

    @Nested
    @DisplayName("round-robin breaks ties, among ONLY the tied users")
    class RoundRobinTieBreak {

        @Test
        void rotatesToTheNextTiedUserAfterTheCursor() {
            var pick = strategy.recommend(
                    ctx(List.of(10L, 20L, 30L), Map.of(10L, 2L, 20L, 2L, 30L, 2L), 10L));

            assertThat(pick).contains(20L);
        }

        @Test
        void wrapsToTheFirstTiedUserWhenTheCursorIsAtTheEnd() {
            var pick = strategy.recommend(
                    ctx(List.of(10L, 20L, 30L), Map.of(10L, 2L, 20L, 2L, 30L, 2L), 30L));

            assertThat(pick).contains(10L);
        }

        @Test
        void anUnsetCursorStartsAtTheFirstTiedUser() {
            var pick = strategy.recommend(
                    ctx(List.of(10L, 20L), Map.of(10L, 1L, 20L, 1L), null));

            assertThat(pick).contains(10L);
        }

        /**
         * The reason the tie-break rotates within the TIED SUBSET rather than the whole candidate
         * list. If it rotated over everyone, a cursor parked on a busy user would hand the lead to
         * whoever follows them in id order — who may not be tied for lowest at all.
         */
        @Test
        void aCursorSittingOnANonTiedUserStillPicksFromTheTiedSubset() {
            // 30 is busy and holds the cursor; 10 and 20 are tied at the bottom.
            var pick = strategy.recommend(
                    ctx(List.of(10L, 20L, 30L), Map.of(10L, 1L, 20L, 1L, 30L, 9L), 30L));

            assertThat(pick).contains(10L);   // wraps within {10,20}, never returns busy 30
        }

        @Test
        void rotationDoesNotOscillateBetweenTwoWhileAThirdTiedUserIsSkipped() {
            List<Long> candidates = List.of(10L, 20L, 30L);
            Map<Long, Long> tied = Map.of(10L, 0L, 20L, 0L, 30L, 0L);

            // Walk the cursor the way the service does: each pick becomes the next cursor.
            Long cursor = null;
            Long first = strategy.recommend(ctx(candidates, tied, cursor)).orElseThrow();
            Long second = strategy.recommend(ctx(candidates, tied, first)).orElseThrow();
            Long third = strategy.recommend(ctx(candidates, tied, second)).orElseThrow();
            Long fourth = strategy.recommend(ctx(candidates, tied, third)).orElseThrow();

            assertThat(List.of(first, second, third)).containsExactly(10L, 20L, 30L);
            assertThat(fourth).isEqualTo(10L);   // full cycle, nobody starved
        }
    }

    @Nested
    @DisplayName("no candidates")
    class EmptyPool {

        /**
         * Empty is reachable — a tenant can deactivate every agent. The strategy declines rather than
         * throwing, so the caller can quarantine the inbound lead instead of failing the webhook.
         */
        @Test
        void recommendsNobodyRatherThanThrowing() {
            assertThat(strategy.recommend(ctx(List.of(), Map.of(), null))).isEmpty();
        }
    }

    @Test
    void typeIsLoadBased() {
        assertThat(strategy.type()).isEqualTo(AssignmentStrategyType.LOAD_BASED);
    }
}
