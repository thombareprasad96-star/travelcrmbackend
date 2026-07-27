package com.crm.travelcrm.platform.console;

import com.crm.travelcrm.auth.repository.SuperAdminRepository;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuperAdminAccountServiceTest {

    private final SuperAdminRepository superAdminRepository = mock(SuperAdminRepository.class);
    private final PlatformAuditRecorder platformAuditRecorder = mock(PlatformAuditRecorder.class);
    private final SuperAdminAccountService service =
            new SuperAdminAccountService(superAdminRepository, platformAuditRecorder);

    @Test
    void resetMfaClearsTargetMfaAndRevokesSessions() {
        SuperAdmin actor = superAdmin(1L, UUID.randomUUID(), "actor@example.com");
        SuperAdmin target = superAdmin(2L, UUID.randomUUID(), "target@example.com");
        target.setMfaEnabled(true);
        target.setMfaSecretEnc("encrypted-secret");
        target.setMfaEnabledAt(LocalDateTime.now().minusDays(2));
        target.setMfaLastUsedAt(LocalDateTime.now().minusHours(1));
        target.setTokenVersion(7);

        when(superAdminRepository.findByIdAndDeletedAtIsNull(actor.getId())).thenReturn(Optional.of(actor));
        when(superAdminRepository.findByPublicIdAndDeletedAtIsNull(target.getPublicId()))
                .thenReturn(Optional.of(target));
        when(superAdminRepository.save(target)).thenReturn(target);

        SuperAdminAccountResponse response =
                service.resetMfa(target.getPublicId(), actor, "203.0.113.10", "unit-test");

        assertThat(target.isMfaEnabled()).isFalse();
        assertThat(target.getMfaSecretEnc()).isNull();
        assertThat(target.getMfaEnabledAt()).isNull();
        assertThat(target.getMfaLastUsedAt()).isNull();
        assertThat(target.getTokenVersionOrZero()).isEqualTo(8);
        assertThat(response.publicId()).isEqualTo(target.getPublicId());
        assertThat(response.mfaEnabled()).isFalse();
        assertThat(response.setupComplete()).isFalse();

        verify(platformAuditRecorder).safeRecord(
                eq(PlatformAuditAction.SUPER_ADMIN_MFA_RESET), eq(true),
                eq(actor.getId()), eq(actor.getEmail()),
                isNull(), isNull(), eq("SUPER_ADMIN"), eq(target.getPublicId()),
                contains("Reset MFA for SuperAdmin target@example.com"),
                eq("203.0.113.10"), eq("unit-test"));
    }

    @Test
    void resetMfaRejectsSelfReset() {
        SuperAdmin actor = superAdmin(1L, UUID.randomUUID(), "actor@example.com");
        actor.setMfaEnabled(true);

        when(superAdminRepository.findByIdAndDeletedAtIsNull(actor.getId())).thenReturn(Optional.of(actor));
        when(superAdminRepository.findByPublicIdAndDeletedAtIsNull(actor.getPublicId()))
                .thenReturn(Optional.of(actor));

        BusinessException ex = catchThrowableOfType(
                () -> service.resetMfa(actor.getPublicId(), actor, "203.0.113.10", "unit-test"),
                BusinessException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(superAdminRepository, never()).save(actor);
        verify(platformAuditRecorder).safeRecord(
                eq(PlatformAuditAction.SUPER_ADMIN_MFA_RESET), eq(false),
                eq(actor.getId()), eq(actor.getEmail()),
                isNull(), isNull(), eq("SUPER_ADMIN"), eq(actor.getPublicId()),
                contains("Blocked self-service SuperAdmin MFA reset"),
                eq("203.0.113.10"), eq("unit-test"));
    }

    private SuperAdmin superAdmin(long id, UUID publicId, String email) {
        SuperAdmin admin = new SuperAdmin();
        admin.setId(id);
        admin.setPublicId(publicId);
        admin.setName(email);
        admin.setEmail(email);
        admin.setEnabled(true);
        admin.setMustChangePassword(false);
        return admin;
    }
}
