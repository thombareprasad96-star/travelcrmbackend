package com.crm.travelcrm.workload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The workload metric, pinned.
 *
 * <p>This formula decides who gets every inbound lead and how the manager's workload tab is ordered.
 * A change here silently redistributes work across a real team, so it is asserted explicitly rather
 * than left to be inferred from two call sites.
 */
class UserWorkloadTest {

    @Test
    @DisplayName("score = todo + inProgress + activeLeads + openReminders")
    void scoreSumsTheFourComponents() {
        assertThat(new UserWorkload(1L, 2, 3, 5, 7).score()).isEqualTo(17);
    }

    @Test
    void anIdleUserScoresZeroAndIsTheMostAttractiveCandidate() {
        assertThat(UserWorkload.empty(1L).score()).isZero();
    }

    /**
     * The bug this metric exists to fix. The calendar used to sort on a task total that counted DONE,
     * so an agent who finished their work climbed the "busiest" list and the manager saw them as
     * loaded — while load-based assignment, which never counted DONE, kept sending them leads.
     */
    @Test
    @DisplayName("finishing work never increases the score — DONE is not in the formula")
    void doneTasksAreNotRepresentable() {
        UserWorkload busy = new UserWorkload(1L, 4, 0, 0, 0);
        UserWorkload finishedThemAll = new UserWorkload(1L, 0, 0, 0, 0);

        assertThat(finishedThemAll.score()).isLessThan(busy.score());
        assertThat(finishedThemAll.score()).isZero();
    }

    @Test
    @DisplayName("each component contributes exactly once")
    void eachComponentIsWeightedEqually() {
        assertThat(new UserWorkload(1L, 1, 0, 0, 0).score()).isEqualTo(1);
        assertThat(new UserWorkload(1L, 0, 1, 0, 0).score()).isEqualTo(1);
        assertThat(new UserWorkload(1L, 0, 0, 1, 0).score()).isEqualTo(1);
        assertThat(new UserWorkload(1L, 0, 0, 0, 1).score()).isEqualTo(1);
    }
}
