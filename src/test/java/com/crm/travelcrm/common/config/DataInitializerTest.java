package com.crm.travelcrm.common.config;

import com.crm.travelcrm.auth.repository.SuperAdminRepository;
import com.crm.travelcrm.auth.security.SuperAdminPasswordPolicy;
import com.crm.travelcrm.common.entity.SuperAdmin;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataInitializerTest {

    private final SuperAdminRepository repository = mock(SuperAdminRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final Environment environment = mock(Environment.class);
    private final SuperAdminPasswordPolicy passwordPolicy = mock(SuperAdminPasswordPolicy.class);
    private final DataInitializer initializer =
            new DataInitializer(repository, passwordEncoder, environment, passwordPolicy);

    @Test
    void createsBothFixedSuperAdminsOnFreshBootstrap() {
        List<SuperAdmin> saved = new ArrayList<>();
        when(repository.findAllByDeletedAtIsNull()).thenReturn(List.of());
        when(repository.findByEmail("rajpoottours2789@gmail.com")).thenReturn(Optional.empty());
        when(repository.findByEmail("thombareprasad96@gmail.com")).thenReturn(Optional.empty());
        when(environment.getProperty("SUPERADMIN_1_EMAIL")).thenReturn("rajpoottours2789@gmail.com");
        when(environment.getProperty("SUPERADMIN_1_PASSWORD")).thenReturn("FirstStrong1!");
        when(environment.getProperty("SUPERADMIN_2_EMAIL")).thenReturn("thombareprasad96@gmail.com");
        when(environment.getProperty("SUPERADMIN_2_PASSWORD")).thenReturn("SecondStrong2!");
        when(passwordEncoder.encode(any())).thenAnswer(invocation -> "hash:" + invocation.getArgument(0));
        when(repository.save(any(SuperAdmin.class))).thenAnswer(invocation -> {
            SuperAdmin admin = invocation.getArgument(0);
            saved.add(admin);
            return admin;
        });
        doNothing().when(passwordPolicy).validate(any());

        initializer.run(new DefaultApplicationArguments());

        assertThat(saved).extracting(SuperAdmin::getEmail)
                .containsExactly("rajpoottours2789@gmail.com", "thombareprasad96@gmail.com");
        assertThat(saved).allSatisfy(admin -> {
            assertThat(admin.isEnabled()).isTrue();
            assertThat(admin.isMfaEnabled()).isFalse();
            assertThat(admin.isMustChangePassword()).isTrue();
            assertThat(admin.isCreatedViaInvite()).isFalse();
            assertThat(admin.getTokenVersionOrZero()).isEqualTo(1);
            assertThat(admin.getFailedLoginAttemptsOrZero()).isZero();
        });
        verify(passwordPolicy).validate("FirstStrong1!");
        verify(passwordPolicy).validate("SecondStrong2!");
    }

    @Test
    void doesNotOverwriteExistingFixedSuperAdmins() {
        SuperAdmin first = SuperAdmin.builder()
                .email("rajpoottours2789@gmail.com")
                .password("existing-hash-1")
                .build();
        SuperAdmin second = SuperAdmin.builder()
                .email("thombareprasad96@gmail.com")
                .password("existing-hash-2")
                .build();
        when(repository.findAllByDeletedAtIsNull()).thenReturn(List.of(first, second));
        when(repository.findByEmail("rajpoottours2789@gmail.com")).thenReturn(Optional.of(first));
        when(repository.findByEmail("thombareprasad96@gmail.com")).thenReturn(Optional.of(second));

        initializer.run(new DefaultApplicationArguments());

        verify(repository, never()).save(any(SuperAdmin.class));
        verify(passwordEncoder, never()).encode(any());
        verify(passwordPolicy, never()).validate(any());
    }

    @Test
    void softDeletesUnexpectedActiveSuperAdminsBeforeEnsuringFixedRows() {
        SuperAdmin unexpected = SuperAdmin.builder()
                .email("superadmin@demo.crm")
                .enabled(true)
                .tokenVersion(2)
                .build();
        when(repository.findAllByDeletedAtIsNull()).thenReturn(List.of(unexpected));
        when(repository.findByEmail("rajpoottours2789@gmail.com")).thenReturn(Optional.of(SuperAdmin.builder()
                .email("rajpoottours2789@gmail.com")
                .build()));
        when(repository.findByEmail("thombareprasad96@gmail.com")).thenReturn(Optional.of(SuperAdmin.builder()
                .email("thombareprasad96@gmail.com")
                .build()));
        when(repository.save(any(SuperAdmin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        initializer.run(new DefaultApplicationArguments());

        verify(repository).save(unexpected);
        assertThat(unexpected.isEnabled()).isFalse();
        assertThat(unexpected.getDeletedAt()).isNotNull();
        assertThat(unexpected.getDeletedBy()).isEqualTo("system-bootstrap");
        assertThat(unexpected.getTokenVersionOrZero()).isEqualTo(3);
    }

    @Test
    void preservesInviteCreatedSuperAdminsOnLaterRestarts() {
        SuperAdmin invited = SuperAdmin.builder()
                .email("invited@example.com")
                .enabled(true)
                .createdViaInvite(true)
                .build();
        when(repository.findAllByDeletedAtIsNull()).thenReturn(List.of(invited));
        when(repository.findByEmail("rajpoottours2789@gmail.com")).thenReturn(Optional.of(SuperAdmin.builder()
                .email("rajpoottours2789@gmail.com")
                .build()));
        when(repository.findByEmail("thombareprasad96@gmail.com")).thenReturn(Optional.of(SuperAdmin.builder()
                .email("thombareprasad96@gmail.com")
                .build()));

        initializer.run(new DefaultApplicationArguments());

        verify(repository, never()).save(any(SuperAdmin.class));
        assertThat(invited.isEnabled()).isTrue();
        assertThat(invited.getDeletedAt()).isNull();
    }
}
