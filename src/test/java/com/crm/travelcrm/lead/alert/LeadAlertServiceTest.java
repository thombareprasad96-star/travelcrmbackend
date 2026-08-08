package com.crm.travelcrm.lead.alert;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.context.TenantTimeZone;
import com.crm.travelcrm.lead.enums.LeadOriginGroups;
import com.crm.travelcrm.lead.enums.LeadStageGroups;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.lead.sla.LeadSlaPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeadAlertServiceTest {

    private static final Long TENANT = 42L;

    private LeadRepository leadRepository;
    private LeadSlaPolicy slaPolicy;
    private LeadAlertService service;

    @BeforeEach
    void setUp() {
        leadRepository = mock(LeadRepository.class);
        LeadAlertAssembler assembler = mock(LeadAlertAssembler.class);
        slaPolicy = mock(LeadSlaPolicy.class);
        TenantTimeZone tenantTimeZone = mock(TenantTimeZone.class);

        when(tenantTimeZone.forTenant(TENANT)).thenReturn(ZoneId.of("Asia/Kolkata"));
        service = new LeadAlertService(leadRepository, assembler, slaPolicy, tenantTimeZone);
        TenantContext.setTenantId(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("the tenant-wide feed requests only machine-created leads")
    void openFeedIsRestrictedToInboundOrigins() {
        when(leadRepository.findOpenToClaim(
                eq(TENANT), eq(LeadOriginGroups.INBOUND_ORIGINS),
                eq(LeadStageGroups.TERMINAL_STAGES), any(Pageable.class)))
                .thenReturn(List.of());

        service.openToClaim();

        verify(leadRepository).findOpenToClaim(
                eq(TENANT), eq(LeadOriginGroups.INBOUND_ORIGINS),
                eq(LeadStageGroups.TERMINAL_STAGES), any(Pageable.class));
    }

    @Test
    @DisplayName("every incoming-lead tile uses the same inbound-origin boundary")
    void statsAreRestrictedToInboundOrigins() {
        when(slaPolicy.targetSecondsForNewLead(TENANT)).thenReturn(300);
        when(leadRepository.avgFirstResponseSeconds(
                eq(TENANT), eq(LeadOriginGroups.INBOUND_ORIGINS),
                any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(null);

        service.stats();

        verify(leadRepository).countInboundCreatedBetween(
                eq(TENANT), eq(LeadOriginGroups.INBOUND_ORIGINS),
                any(LocalDateTime.class), any(LocalDateTime.class));
        verify(leadRepository).countOpenToClaim(
                TENANT, LeadOriginGroups.INBOUND_ORIGINS, LeadStageGroups.TERMINAL_STAGES);
        verify(leadRepository).avgFirstResponseSeconds(
                eq(TENANT), eq(LeadOriginGroups.INBOUND_ORIGINS),
                any(LocalDateTime.class), any(LocalDateTime.class));
        verify(leadRepository).countSlaBreaches(
                eq(TENANT), eq(LeadOriginGroups.INBOUND_ORIGINS),
                any(LocalDateTime.class), any(LocalDateTime.class), any(LocalDateTime.class),
                eq(300), eq(LeadStageGroups.TERMINAL_STAGES));
        verify(leadRepository, never()).countCreatedBetween(
                eq(TENANT), any(LocalDateTime.class), any(LocalDateTime.class));
    }
}
