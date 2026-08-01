package com.crm.travelcrm.lead.assignment.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.lead.assignment.repository.LeadAssignmentPointerRepository;
import com.crm.travelcrm.lead.assignment.strategy.AssignmentStrategyType;
import com.crm.travelcrm.lead.assignment.strategy.LeadAssignmentStrategyResolver;
import com.crm.travelcrm.permission.enums.Permission;
import com.crm.travelcrm.permission.service.ScopeResolver;
import com.crm.travelcrm.workload.WorkloadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LeadAssignmentServiceTest {

    private static final Long TENANT = 11L;

    private UserRepository userRepository;
    private AssignableUserResolver assignableUserResolver;
    private LeadAssignmentPointerRepository pointerRepository;
    private LeadAssignmentPointerProvisioner pointerProvisioner;
    private LeadAssignmentStrategyResolver strategyResolver;
    private ScopeResolver scopeResolver;
    private WorkloadService workloadService;
    private LeadAssignmentService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        assignableUserResolver = mock(AssignableUserResolver.class);
        pointerRepository = mock(LeadAssignmentPointerRepository.class);
        pointerProvisioner = mock(LeadAssignmentPointerProvisioner.class);
        strategyResolver = mock(LeadAssignmentStrategyResolver.class);
        scopeResolver = mock(ScopeResolver.class);
        workloadService = mock(WorkloadService.class);

        service = new LeadAssignmentService(userRepository, assignableUserResolver, pointerRepository,
                pointerProvisioner, strategyResolver, scopeResolver, workloadService);
    }

    @Test
    void inboundLeadFallsBackToTenantAdminWhenThereAreNoActiveNonAdminCandidates() {
        User admin = user(101L, Role.TENANT_ADMIN, "Tenant Admin");
        when(assignableUserResolver.resolve(eq(TENANT), eq(Permission.LEAD_READ)))
                .thenReturn(List.of(admin));

        Optional<AssignmentOutcome> outcome = service.assignForInbound(TENANT);

        assertThat(outcome).isPresent();
        assertThat(outcome.orElseThrow().finalUser()).isSameAs(admin);
        assertThat(outcome.orElseThrow().recommendedUserId()).isEqualTo(admin.getId());
        assertThat(outcome.orElseThrow().strategyUsed()).isEqualTo(AssignmentStrategyType.LOAD_BASED);
        assertThat(outcome.orElseThrow().manualOverride()).isFalse();
        verifyNoInteractions(pointerProvisioner, pointerRepository, strategyResolver, workloadService,
                userRepository, scopeResolver);
    }

    @Test
    void inboundLeadStillReturnsEmptyWhenThereIsNoEligibleAdminFallback() {
        when(assignableUserResolver.resolve(eq(TENANT), eq(Permission.LEAD_READ)))
                .thenReturn(List.of());

        assertThat(service.assignForInbound(TENANT)).isEmpty();
        verifyNoInteractions(pointerProvisioner, pointerRepository, strategyResolver, workloadService,
                userRepository, scopeResolver);
    }

    private static User user(long id, Role role, String name) {
        User user = User.builder()
                .tenantId(TENANT)
                .role(role)
                .name(name)
                .email(name.replace(' ', '.').toLowerCase() + "@example.com")
                .password("secret")
                .build();
        user.setId(id);
        user.setPublicId(UUID.randomUUID());
        return user;
    }
}
