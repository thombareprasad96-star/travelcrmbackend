package com.crm.travelcrm.portal.auth.service;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.UnauthorizedException;
import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.repository.CustomerRepository;
import com.crm.travelcrm.otp.OtpChannel;
import com.crm.travelcrm.otp.OtpPurpose;
import com.crm.travelcrm.otp.OtpResult;
import com.crm.travelcrm.otp.service.OtpService;
import com.crm.travelcrm.portal.auth.dto.PortalLoginResponse;
import com.crm.travelcrm.portal.auth.entity.TravelerAccount;
import com.crm.travelcrm.portal.auth.entity.TravelerAccountStatus;
import com.crm.travelcrm.portal.auth.repository.TravelerAccountRepository;
import com.crm.travelcrm.portal.security.PortalJwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Traveler login = identity resolution + the shared {@link OtpService}. This service owns the
 * <em>identity</em> half (resolve the customer/account, issue the token); the OTP module owns the
 * <em>passcode</em> half (generate/store/deliver/verify) and is reused as-is.
 *
 * <p><b>Tenant flow.</b> The first lookup deliberately resolves the {@code Customer} cross-tenant
 * from the identifier (no tenant known yet → tenant filter inactive). Its tenant is then set on
 * {@code TenantContext} so the account write is stamped/scoped; it is NOT cleared here — the portal
 * filter's {@code finally} clears it at request end (same contract as the staff path), so the
 * commit-time entity listener still sees the right tenant.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TravelerAuthServiceImpl implements TravelerAuthService {

    private final CustomerRepository customerRepository;
    private final TravelerAccountRepository travelerAccountRepository;
    private final PortalJwtUtil portalJwtUtil;
    private final OtpService otpService;

    @Override
    @Transactional
    public void requestOtp(String identifier) {
        String id = normalize(identifier);
        Customer customer = resolveCustomer(id).orElse(null);
        if (customer == null) {
            log.info("[PORTAL] OTP request for unknown identifier — silently ignored");
            return;   // no leak: the controller responds 200 regardless
        }
        TenantContext.setTenantId(customer.getTenantId());   // cleared by the portal filter's finally

        TravelerAccount account = travelerAccountRepository
                .findByTenantIdAndCustomerIdAndDeletedAtIsNull(customer.getTenantId(), customer.getId())
                .orElseGet(() -> provision(customer, id));
        if (account.getStatus() == TravelerAccountStatus.DISABLED) {
            log.warn("[PORTAL] OTP request for DISABLED account {}", account.getPublicId());
            return;
        }
        // Delivery channel is inferred (email vs SMS) from the identifier by the OTP module.
        otpService.request(OtpPurpose.PORTAL_LOGIN, id, OtpChannel.AUTO);
    }

    @Override
    @Transactional
    public PortalLoginResponse verifyOtp(String identifier, String otp) {
        String id = normalize(identifier);
        Customer customer = resolveCustomer(id).orElseThrow(this::invalid);
        TenantContext.setTenantId(customer.getTenantId());

        TravelerAccount account = travelerAccountRepository
                .findByTenantIdAndCustomerIdAndDeletedAtIsNull(customer.getTenantId(), customer.getId())
                .filter(TravelerAccount::isActive)
                .orElseThrow(this::invalid);

        // Credential check delegated entirely to the shared OTP module.
        OtpResult result = otpService.verify(OtpPurpose.PORTAL_LOGIN, id, otp);
        switch (result) {
            case SUCCESS -> { /* proceed to issue a token */ }
            case EXPIRED -> throw new UnauthorizedException("Your code has expired. Please request a new one.");
            case TOO_MANY_ATTEMPTS -> throw new UnauthorizedException("Too many attempts. Please request a new code.");
            default -> throw new UnauthorizedException("Invalid code.");   // INVALID / NOT_FOUND
        }

        account.setLastLoginAt(Instant.now());
        travelerAccountRepository.save(account);
        log.info("[PORTAL] traveler {} logged in (customer {})",
                account.getPublicId(), account.getCustomerPublicId());

        return PortalLoginResponse.builder()
                .token(portalJwtUtil.generateToken(account))
                .expiresInMs(portalJwtUtil.getExpiryMs())
                .customerPublicId(account.getCustomerPublicId())
                .name(account.getCustomerName())
                .build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Cross-tenant identity resolution — the ONLY pre-tenant query (see class javadoc). */
    private Optional<Customer> resolveCustomer(String id) {
        return id.contains("@")
                ? customerRepository.findFirstByEmailAndDeletedAtIsNullOrderByIdAsc(id)
                : customerRepository.findFirstByPhoneAndDeletedAtIsNullOrderByIdAsc(id);
    }

    private TravelerAccount provision(Customer customer, String identifier) {
        TravelerAccount account = TravelerAccount.builder()
                .tenantId(customer.getTenantId())
                .customerId(customer.getId())
                .customerPublicId(customer.getPublicId())
                .customerName(customer.getName())
                .loginIdentifier(identifier)
                .status(TravelerAccountStatus.ACTIVE)
                .build();
        TravelerAccount saved = travelerAccountRepository.save(account);
        log.info("[PORTAL] provisioned traveler account {} for customer {}",
                saved.getPublicId(), customer.getPublicId());
        return saved;
    }

    private String normalize(String identifier) {
        String trimmed = identifier == null ? "" : identifier.trim();
        return trimmed.contains("@") ? trimmed.toLowerCase() : trimmed;
    }

    private UnauthorizedException invalid() {
        return new UnauthorizedException("Invalid code or it has expired.");
    }
}
