package com.crm.travelcrm.task;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.settings.entity.TenantSettings;
import com.crm.travelcrm.settings.repository.TenantSettingsRepository;
import com.crm.travelcrm.task.entity.Task;
import com.crm.travelcrm.task.enums.TaskStatus;
import com.crm.travelcrm.task.repository.TaskRepository;
import com.crm.travelcrm.task.scheduler.TaskOverdueScanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TaskOverdueScanner} — the only genuinely new runtime behaviour in the
 * All Tasks feature, and the piece with the most ways to be subtly wrong.
 *
 * <p>Pure Mockito, no Spring context: the four context-loading tests in this repo currently boot
 * against a real Postgres with Flyway validate, so anything {@code @SpringBootTest} would be
 * blocked by an unrelated migration checksum rather than by its own correctness.
 */
class TaskOverdueScannerTest {

    private TaskRepository taskRepository;
    private TenantSettingsRepository tenantSettingsRepository;
    private ApplicationEventPublisher eventPublisher;
    private TaskOverdueScanner scanner;

    private static final Long TENANT = 7L;
    private static final Long ASSIGNEE = 42L;
    private static final Long CREATOR = 99L;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        tenantSettingsRepository = mock(TenantSettingsRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        scanner = new TaskOverdueScanner(taskRepository, tenantSettingsRepository, eventPublisher);
        ReflectionTestUtils.setField(scanner, "batchSize", 100);
        ReflectionTestUtils.setField(scanner, "enabled", true);

        when(taskRepository.saveAndFlush(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────────────────────

    private Task overdueTask() {
        Task t = new Task();
        t.setPublicId(UUID.randomUUID());
        t.setTenantId(TENANT);
        t.setTitle("Call back");
        t.setStatus(TaskStatus.TODO);
        t.setDueDate(Instant.now().minusSeconds(3600));
        t.setAssignToUserId(ASSIGNEE);
        t.setOwnerUserId(CREATOR);
        return t;
    }

    private void givenDue(Task... tasks) {
        when(taskRepository.findOverdueAwaitingAlert(any(Collection.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(tasks));
    }

    private void givenSettings(boolean whatsApp, boolean email) {
        TenantSettings s = new TenantSettings();
        s.setTaskOverdueAlertWhatsApp(whatsApp);
        s.setTaskOverdueAlertEmail(email);
        when(tenantSettingsRepository.findByTenantId(TENANT)).thenReturn(Optional.of(s));
    }

    private NotifyEvent captureEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(NotifyEvent.class);
        return (NotifyEvent) captor.getValue();
    }

    // ── Who gets told ────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("recipient")
    class Recipient {

        @Test
        @DisplayName("alerts the ASSIGNEE, not the creator — the defect ReminderScheduler has")
        void alertsAssigneeNotCreator() {
            givenDue(overdueTask());
            givenSettings(false, false);

            scanner.scanOverdueTasks();

            NotifyEvent event = captureEvent();
            assertThat(event.getRecipientUserIds()).containsExactly(ASSIGNEE);
            assertThat(event.getRecipientUserIds()).doesNotContain(CREATOR);
        }

        @Test
        @DisplayName("recipients are always explicit, so the in-app channel never falls back to admins")
        void recipientsAreNeverEmpty() {
            givenDue(overdueTask());
            givenSettings(false, false);

            scanner.scanOverdueTasks();

            assertThat(captureEvent().getRecipientUserIds()).isNotEmpty();
        }

        @Test
        @DisplayName("publishes TASK_OVERDUE with a TASK reference so the bell can deep-link")
        void publishesRoutableEvent() {
            Task task = overdueTask();
            givenDue(task);
            givenSettings(false, false);

            scanner.scanOverdueTasks();

            NotifyEvent event = captureEvent();
            assertThat(event.getType()).isEqualTo("TASK_OVERDUE");
            assertThat(event.getReferenceType()).isEqualTo("TASK");
            assertThat(event.getReferencePublicId()).isEqualTo(task.getPublicId());
            assertThat(event.getTenantId()).isEqualTo(TENANT);
        }
    }

    // ── Channel opt-in ───────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("channels")
    class Channels {

        @Test
        @DisplayName("IN_APP only when the tenant has opted into neither noisy channel")
        void defaultsToInAppOnly() {
            givenDue(overdueTask());
            givenSettings(false, false);

            scanner.scanOverdueTasks();

            assertThat(captureEvent().getChannels()).containsExactly(DeliveryChannel.IN_APP);
        }

        @Test
        @DisplayName("adds WhatsApp and email only when the tenant switched them on")
        void honoursTenantOptIn() {
            givenDue(overdueTask());
            givenSettings(true, true);

            scanner.scanOverdueTasks();

            assertThat(captureEvent().getChannels())
                    .containsExactlyInAnyOrder(
                            DeliveryChannel.IN_APP, DeliveryChannel.WHATSAPP, DeliveryChannel.EMAIL);
        }

        @Test
        @DisplayName("a tenant with no settings row still gets the in-app alert")
        void missingSettingsDegradesToInApp() {
            givenDue(overdueTask());
            when(tenantSettingsRepository.findByTenantId(TENANT)).thenReturn(Optional.empty());

            scanner.scanOverdueTasks();

            assertThat(captureEvent().getChannels()).containsExactly(DeliveryChannel.IN_APP);
        }

        @Test
        @DisplayName("settings are read once per tenant, not once per task")
        void settingsLookupIsPerTenant() {
            givenDue(overdueTask(), overdueTask(), overdueTask());
            givenSettings(false, false);

            scanner.scanOverdueTasks();

            verify(tenantSettingsRepository, org.mockito.Mockito.times(1)).findByTenantId(TENANT);
        }
    }

    // ── Idempotency ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("stamps overdueNotifiedAt so the next tick cannot re-alert the same task")
        void stampsTheMarker() {
            Task task = overdueTask();
            assertThat(task.getOverdueNotifiedAt()).isNull();
            givenDue(task);
            givenSettings(false, false);

            scanner.scanOverdueTasks();

            assertThat(task.getOverdueNotifiedAt()).isNotNull();
            verify(taskRepository).saveAndFlush(task);
        }

        @Test
        @DisplayName("the poll itself excludes already-alerted rows (guard lives in the query)")
        void pollAsksOnlyForOpenUnalertedRows() {
            givenDue();
            scanner.scanOverdueTasks();

            ArgumentCaptor<Collection> statuses = ArgumentCaptor.forClass(Collection.class);
            verify(taskRepository).findOverdueAwaitingAlert(
                    statuses.capture(), any(Instant.class), any(Pageable.class));
            assertThat(statuses.getValue())
                    .containsExactlyInAnyOrder(TaskStatus.TODO, TaskStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("nothing due ⇒ nothing published and nothing written")
        void quietWhenNothingIsDue() {
            givenDue();

            scanner.scanOverdueTasks();

            verify(eventPublisher, never()).publishEvent(any(Object.class));
            verify(taskRepository, never()).saveAndFlush(any(Task.class));
        }
    }

    // ── Resilience ───────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resilience")
    class Resilience {

        @Test
        @DisplayName("one failing task does not stop the sweep")
        void oneFailureDoesNotAbortTheBatch() {
            Task bad = overdueTask();
            Task good = overdueTask();
            givenDue(bad, good);
            givenSettings(false, false);
            doThrow(new RuntimeException("boom"))
                    .when(taskRepository).saveAndFlush(bad);

            scanner.scanOverdueTasks();

            // Both were attempted; the good one still got its marker.
            verify(taskRepository).saveAndFlush(good);
            assertThat(good.getOverdueNotifiedAt()).isNotNull();
        }

        @Test
        @DisplayName("TenantContext is cleared after the sweep, so a pooled thread cannot leak a tenant")
        void clearsTenantContext() {
            givenDue(overdueTask());
            givenSettings(false, false);

            scanner.scanOverdueTasks();

            assertThat(TenantContext.getTenantId()).isNull();
        }

        @Test
        @DisplayName("TenantContext is cleared even when a row blows up")
        void clearsTenantContextOnFailure() {
            Task bad = overdueTask();
            givenDue(bad);
            givenSettings(false, false);
            doThrow(new RuntimeException("boom")).when(taskRepository).saveAndFlush(bad);

            scanner.scanOverdueTasks();

            assertThat(TenantContext.getTenantId()).isNull();
        }

        @Test
        @DisplayName("the kill switch stops it dead — no query at all")
        void disabledDoesNothing() {
            ReflectionTestUtils.setField(scanner, "enabled", false);

            scanner.scanOverdueTasks();

            verify(taskRepository, never())
                    .findOverdueAwaitingAlert(any(Collection.class), any(Instant.class), any(Pageable.class));
        }
    }
}
