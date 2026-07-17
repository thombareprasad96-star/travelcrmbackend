package com.crm.travelcrm.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TenantScope}'s three invariants, each of which exists to close a specific fail-open.
 */
class TenantScopeTest {

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Nested
    @DisplayName("1. null-rejecting — a null tenant makes TenantFilterAspect fail OPEN")
    class NullRejecting {

        @Test
        void rejectsANullTenantId() {
            assertThatThrownBy(() -> TenantScope.run(null, () -> {}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-null");
        }

        @Test
        void aNullTenantIsRejectedBeforeTheWorkRuns() {
            boolean[] ran = {false};

            assertThatThrownBy(() -> TenantScope.run(null, () -> ran[0] = true))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(ran[0]).isFalse();
        }
    }

    @Nested
    @DisplayName("2. save/restore — TenantContext is a bare ThreadLocal with no stack")
    class SaveRestore {

        @Test
        void setsTheTenantForTheDurationOfTheWork() {
            Long seen = TenantScope.call(7L, TenantContext::getTenantId);

            assertThat(seen).isEqualTo(7L);
        }

        @Test
        void clearsAfterwardsWhenThereWasNoOuterTenant() {
            TenantScope.run(7L, () -> {});

            assertThat(TenantContext.getTenantId()).isNull();
        }

        /**
         * The invariant a naive set/clear breaks. Without save/restore, the inner block's clear()
         * destroys the OUTER tenant and everything after it runs unfiltered — silently.
         */
        @Test
        void restoresTheOuterTenantAfterNesting() {
            TenantContext.setTenantId(1L);

            TenantScope.run(2L, () -> assertThat(TenantContext.getTenantId()).isEqualTo(2L));

            assertThat(TenantContext.getTenantId()).isEqualTo(1L);
        }

        @Test
        void restoresEvenWhenTheWorkThrows() {
            TenantContext.setTenantId(1L);

            assertThatThrownBy(() -> TenantScope.run(2L, () -> {
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(TenantContext.getTenantId()).isEqualTo(1L);
        }

        @Test
        void clearsEvenWhenTheWorkThrowsAndThereWasNoOuterTenant() {
            assertThatThrownBy(() -> TenantScope.run(2L, () -> {
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(TenantContext.getTenantId()).isNull();
        }
    }

    @Nested
    @DisplayName("3. the transaction guard — the point of the whole helper")
    class TransactionGuard {

        /**
         * TenantFilterAspect is @Before on @Transactional, so entering a scope INSIDE a transaction is
         * too late — the filter is already latched off for that whole transaction. Invisible in
         * single-tenant dev; a cross-tenant leak in production. The guard turns it into a loud throw
         * at the offending call site.
         */
        @Test
        void refusesToBeEnteredInsideAnActiveTransaction() {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThatThrownBy(() -> TenantScope.run(1L, () -> {}))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("BEFORE a transaction begins");
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        }

        @Test
        void isFineOutsideATransaction() {
            assertThatCode(() -> TenantScope.run(1L, () -> {})).doesNotThrowAnyException();
        }
    }

    @Test
    void callReturnsTheWorkResult() {
        assertThat(TenantScope.call(1L, () -> "result")).isEqualTo("result");
    }
}
