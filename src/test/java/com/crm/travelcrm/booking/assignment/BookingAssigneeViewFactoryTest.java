package com.crm.travelcrm.booking.assignment;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.common.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resolving a booking's assignee for display.
 *
 * <p>The first test is a regression guard for a bug that shipped and was caught only by an
 * end-to-end conversion: both booking-create paths publish a notification <i>before</i> mapping
 * their response, and {@code NotifyEventListener} clears {@code TenantContext} synchronously on the
 * publisher's own thread. A factory that scoped its read by the ThreadLocal therefore found no
 * tenant and rendered <b>every freshly-created booking as unassigned</b> — while the database row
 * was perfectly correct. Reading the tenant off the booking is what makes that unreachable.
 */
class BookingAssigneeViewFactoryTest {

    private static final Long TENANT = 1L;

    private UserRepository userRepository;
    private BookingAssigneeViewFactory factory;
    private User agent;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        factory = new BookingAssigneeViewFactory(userRepository);

        agent = User.builder().name("Demo Agent").build();
        agent.setId(3L);
        agent.setPublicId(UUID.randomUUID());

        when(userRepository.findByIdInAndTenantId(anyCollection(), eq(TENANT)))
                .thenReturn(List.of(agent));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Booking booking(Long tenantId, Long assignedUserId) {
        Booking b = new Booking();
        b.setTenantId(tenantId);
        b.setAssignedUserId(assignedUserId);
        return b;
    }

    @Test
    @DisplayName("resolves the assignee even when TenantContext has been wiped mid-request")
    void resolvesWithNoTenantOnTheThread() {
        // Exactly the state a response is mapped in after publishBookingEvent(): the ThreadLocal
        // is gone. The booking still knows its own tenant, so the name must still resolve.
        TenantContext.clear();

        var view = factory.of(booking(TENANT, agent.getId()));

        assertThat(view.nameOf(agent.getId())).isEqualTo("Demo Agent");
        assertThat(view.publicIdOf(agent.getId())).isEqualTo(agent.getPublicId());
    }

    @Test
    @DisplayName("ignores TenantContext even when it disagrees with the booking")
    void trustsTheBookingNotTheThreadLocal() {
        TenantContext.setTenantId(999L);   // a stale/foreign value on the thread

        assertThat(factory.of(booking(TENANT, agent.getId())).nameOf(agent.getId()))
                .isEqualTo("Demo Agent");
    }

    @Test
    @DisplayName("an unassigned booking costs no query")
    void skipsTheReadEntirely() {
        var view = factory.of(booking(TENANT, null));

        assertThat(view.nameOf(null)).isNull();
        verify(userRepository, never()).findByIdInAndTenantId(anyCollection(), eq(TENANT));
    }

    @Test
    @DisplayName("unknown / since-deleted assignee renders blank rather than throwing")
    void toleratesAnUnresolvableAssignee() {
        var view = factory.of(booking(TENANT, 404L));

        assertThat(view.nameOf(404L)).isNull();
        assertThat(view.publicIdOf(404L)).isNull();
    }

    @Test
    @DisplayName("a set spanning two tenants is an isolation failure, not a display quirk")
    void refusesToRenderAcrossTenants() {
        assertThatThrownBy(() -> factory.of(List.of(
                booking(TENANT, agent.getId()),
                booking(2L, agent.getId()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("across 2 tenants");
    }
}
