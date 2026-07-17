package com.crm.travelcrm.tenent.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.booking.cancellation.service.CancellationPolicySeeder;
import com.crm.travelcrm.common.context.PlatformActor;
import com.crm.travelcrm.common.context.PlatformContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.platform.billing.enums.BillingStatus;
import com.crm.travelcrm.platform.billing.proration.ProrationCalculator;
import com.crm.travelcrm.platform.billing.proration.ProrationResult;
import com.crm.travelcrm.platform.billing.repository.BillingRecordRepository;
import com.crm.travelcrm.platform.billing.service.BillingService;
import com.crm.travelcrm.platform.subscription.entity.Plan;
import com.crm.travelcrm.platform.subscription.repository.PlanRepository;
import com.crm.travelcrm.tenent.dto.CreateTenantRequest;
import com.crm.travelcrm.tenent.dto.TenantResponse;
import com.crm.travelcrm.tenent.dto.TenantSummaryResponse;
import com.crm.travelcrm.tenent.dto.UpdateTenantRequest;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.enums.TenantPlan;
import com.crm.travelcrm.tenent.enums.TenantStatus;
import com.crm.travelcrm.tenent.exception.DuplicateTenantException;
import com.crm.travelcrm.tenent.exception.TenantNotFoundException;
import com.crm.travelcrm.tenent.mapper.TenantMapper;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantServiceImpl implements TenantService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final TenantMapper tenantMapper;
    private final PasswordEncoder passwordEncoder;
    private final PlatformAuditRecorder platformAuditRecorder;
    private final PlanRepository planRepository;
    private final BillingRecordRepository billingRecordRepository;
    private final CancellationPolicySeeder cancellationPolicySeeder;
    private final BillingService billingService;
    private final ProrationCalculator prorationCalculator;

    // ── create ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public TenantResponse createTenant(CreateTenantRequest request) {
        log.info("Creating tenant: {}", request.getOrganizationCode());

        if (tenantRepository.existsByOrganizationCode(request.getOrganizationCode())) {
            throw new DuplicateTenantException(
                    "Organization code already exists: " + request.getOrganizationCode());
        }
        if (tenantRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateTenantException("Email already registered: " + request.getEmail());
        }

        // The admin's LOGIN email — a different thing from the organization's contact email checked
        // above, and against a different table. Staff email is unique platform-wide, so this must be
        // a global check: without it this flow mints a duplicate that breaks login for both accounts.
        String adminEmail = request.getAdminEmail().trim().toLowerCase();
        if (userRepository.existsByEmail(adminEmail)) {
            throw new DuplicateTenantException("Admin email already registered: " + adminEmail);
        }

        TenantPlan plan = request.getPlan() != null ? request.getPlan() : TenantPlan.STARTER;
        TenantStatus status = request.getStatus() != null ? request.getStatus() : TenantStatus.TRIAL;
        int maxUsers = request.getMaxUsers() != null ? request.getMaxUsers() : 5;

        Tenant tenant = Tenant.builder()
                .organizationName(request.getOrganizationName())
                .organizationCode(request.getOrganizationCode().toUpperCase())
                .email(request.getEmail())
                .phone(request.getPhone())
                .address(request.getAddress())
                .plan(plan)
                .status(status)
                .maxUsers(maxUsers)
                .subscriptionStartDate(request.getSubscriptionStartDate())
                .subscriptionEndDate(request.getSubscriptionEndDate())
                .build();

        // Seed plan entitlements (module access + lead/booking/storage caps) from the plan catalogue.
        // orElseThrow, not ifPresent: a missing plan row used to no-op silently, saving a tenant with
        // no modules and null caps. That tenant could never be repaired — ModuleAccessFilter 403s every
        // CRM endpoint, and the console's updateModules whitelists against availableModules(), which is
        // the union of persisted plan modules and therefore also empty. It presented as a broken
        // permission system rather than an empty catalogue. Fail here instead, where the cause is legible.
        Plan planRow = planRepository.findByCode(plan).orElseThrow(() -> new IllegalStateException(
                "Plan '" + plan + "' is not in the plan catalogue — cannot create tenant '"
                + request.getOrganizationCode() + "'. The plans table is seeded on startup by "
                + "PlanCatalogueInitializer; an empty catalogue means that runner did not complete."));
        tenant.setMaxLeads(planRow.getMaxLeads());
        tenant.setMaxBookingsPerMonth(planRow.getMaxBookingsPerMonth());
        tenant.setMaxStorageMb(planRow.getMaxStorageMb());
        tenant.setMaxSubAgents(planRow.getMaxSubAgents());
        tenant.setEnabledModules(new HashSet<>(planRow.getModules()));

        Tenant saved = tenantRepository.save(tenant);
        log.info("Tenant saved with id: {}", saved.getId());

        // First TENANT_ADMIN for this tenant.
        User adminUser = User.builder()
                .name(request.getAdminUsername())
                .email(adminEmail)
                .password(passwordEncoder.encode(request.getAdminPassword()))
                .role(Role.TENANT_ADMIN)
                .tenantId(saved.getId())
                .isActive(true)
                .build();
        userRepository.save(adminUser);
        log.info("Admin user created for tenant id: {}", saved.getId());

        // Seed the conservative tiered cancellation-charge default so the tenant can cancel/refund
        // from day one (without it, resolution would fall through to a zero charge). Same transaction.
        cancellationPolicySeeder.ensureCompanyDefault(saved.getId());

        audit(PlatformAuditAction.TENANT_CREATE, saved,
                "Created tenant " + saved.getOrganizationName()
                        + " (" + plan + "/" + status + ", admin " + request.getAdminEmail() + ")");

        TenantResponse response = tenantMapper.toResponse(saved);
        response.setUserCount(1L);
        response.setAdminUsername(request.getAdminUsername());
        response.setMessage("Tenant created successfully");
        return response;
    }

    // ── read ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<TenantSummaryResponse> listTenants(String search, TenantStatus status,
                                                   boolean deleted, Pageable pageable) {
        Page<Tenant> page = tenantRepository.search(
                search == null ? "" : search.trim(), status, deleted, pageable);

        List<Long> ids = page.getContent().stream().map(Tenant::getId).toList();
        Map<Long, Long> userCounts = ids.isEmpty()
                ? Map.of()
                : userRepository.countActiveGroupedByTenant(ids).stream()
                        .collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));
        Map<Long, Long> unpaidCounts = ids.isEmpty()
                ? Map.of()
                : billingRecordRepository.countByStatusGroupedByTenant(BillingStatus.UNPAID, ids).stream()
                        .collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));

        return page.map(t -> tenantMapper.toSummary(t,
                userCounts.getOrDefault(t.getId(), 0L),
                unpaidCounts.getOrDefault(t.getId(), 0L)));
    }

    @Override
    @Transactional(readOnly = true)
    public TenantResponse getTenant(UUID publicId) {
        Tenant tenant = tenantRepository.findByPublicId(publicId)
                .orElseThrow(() -> new TenantNotFoundException(publicId));
        TenantResponse response = tenantMapper.toResponse(tenant);
        response.setUserCount(userRepository.countByTenantIdAndDeletedAtIsNull(tenant.getId()));
        return response;
    }

    // ── update basics ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public TenantResponse updateTenant(UUID publicId, UpdateTenantRequest request) {
        Tenant tenant = requireLive(publicId);

        if (tenantRepository.existsByEmailAndIdNot(request.getEmail(), tenant.getId())) {
            throw new DuplicateTenantException("Email already in use: " + request.getEmail());
        }

        tenantMapper.updateEntity(request, tenant);
        Tenant saved = tenantRepository.save(tenant);

        audit(PlatformAuditAction.TENANT_UPDATE, saved,
                "Updated tenant " + saved.getOrganizationName());

        TenantResponse response = tenantMapper.toResponse(saved);
        response.setUserCount(userRepository.countByTenantIdAndDeletedAtIsNull(saved.getId()));
        return response;
    }

    // ── plan assignment (upgrade / downgrade) ────────────────────────────────────

    @Override
    @Transactional
    public TenantResponse changePlan(UUID publicId, TenantPlan planCode) {
        Tenant tenant = requireLive(publicId);
        Plan plan = planRepository.findByCode(planCode)
                .orElseThrow(() -> new BusinessException(
                        "Unknown plan: " + planCode, HttpStatus.BAD_REQUEST));

        TenantPlan previousPlan = tenant.getPlan();   // captured before the switch for proration
        tenant.setPlan(planCode);
        // Module access always re-syncs to the new plan. Numeric limits re-sync from the plan too,
        // UNLESS a SuperAdmin has pinned this tenant's quota via an override (then the override wins).
        tenant.setEnabledModules(new HashSet<>(plan.getModules()));
        if (!tenant.isQuotaOverride()) {
            // null users = unlimited (kept as the historical 1_000_000 sentinel used by the seat cap).
            tenant.setMaxUsers(plan.getMaxUsers() != null ? plan.getMaxUsers() : 1_000_000);
            tenant.setMaxLeads(plan.getMaxLeads());
            tenant.setMaxBookingsPerMonth(plan.getMaxBookingsPerMonth());
            tenant.setMaxStorageMb(plan.getMaxStorageMb());
            // Purchased add-on sub-agent seats survive a plan change: cap = plan default + paid seats
            // (mirror TenantPlanApplier.applyPlan — the two MUST stay in sync).
            int planSeats = plan.getMaxSubAgents() != null ? Math.max(0, plan.getMaxSubAgents()) : 0;
            int purchasedSeats = tenant.getPurchasedSubAgentSeats() != null ? Math.max(0, tenant.getPurchasedSubAgentSeats()) : 0;
            tenant.setMaxSubAgents(planSeats + purchasedSeats);
        }
        Tenant saved = tenantRepository.save(tenant);

        audit(PlatformAuditAction.PLAN_CHANGE, saved,
                "Changed plan to " + planCode + " for " + saved.getOrganizationName());

        // Charge/credit the prorated difference for the rest of this billing month.
        maybeIssueProration(saved, previousPlan, planCode);

        TenantResponse response = tenantMapper.toResponse(saved);
        response.setUserCount(userRepository.countByTenantIdAndDeletedAtIsNull(saved.getId()));
        return response;
    }

    /**
     * Issue a proration line for a mid-cycle plan change. Only ACTIVE (live, paying) tenants are
     * prorated — a TRIAL/SUSPENDED/EXPIRED/PAST_DUE tenant has no in-progress paid cycle to adjust,
     * and same-price or same-plan changes produce no line. The window is the current calendar month,
     * matching how {@code BillingServiceImpl.create} issues monthly invoices.
     */
    private void maybeIssueProration(Tenant tenant, TenantPlan previousPlan, TenantPlan newPlan) {
        if (previousPlan == newPlan || tenant.getStatus() != TenantStatus.ACTIVE) {
            return;
        }
        Plan oldPlanEntity = planRepository.findByCode(previousPlan).orElse(null);
        Plan newPlanEntity = planRepository.findByCode(newPlan).orElse(null);
        // Never net two monthly prices across different currencies — the raw difference is meaningless.
        if (oldPlanEntity != null && newPlanEntity != null
                && oldPlanEntity.getCurrency() != null && newPlanEntity.getCurrency() != null
                && !oldPlanEntity.getCurrency().equalsIgnoreCase(newPlanEntity.getCurrency())) {
            log.warn("Skipping proration for tenant {}: plan currencies differ ({} → {})",
                    tenant.getId(), oldPlanEntity.getCurrency(), newPlanEntity.getCurrency());
            return;
        }
        BigDecimal oldMonthly = oldPlanEntity != null && oldPlanEntity.getMonthlyPrice() != null
                ? oldPlanEntity.getMonthlyPrice() : BigDecimal.ZERO;
        BigDecimal newMonthly = newPlanEntity != null && newPlanEntity.getMonthlyPrice() != null
                ? newPlanEntity.getMonthlyPrice() : BigDecimal.ZERO;

        LocalDate today = LocalDate.now();
        LocalDate periodStart = today.withDayOfMonth(1);
        LocalDate periodEnd = YearMonth.from(today).atEndOfMonth();

        ProrationResult result = prorationCalculator.calculate(
                oldMonthly, newMonthly, today, periodStart, periodEnd);
        if (!result.hasAdjustment()) {
            return;
        }

        String notes = (result.isCharge() ? "Proration: upgrade " : "Proration: downgrade ")
                + previousPlan + " → " + newPlan + " for " + result.remainingDays() + " of "
                + result.periodDays() + " days (" + periodStart + " to " + periodEnd + ").";
        // The line covers today → month-end (the days billed at the new rate).
        billingService.issueProration(tenant.getId(), newPlan, result.amount(), today, periodEnd, notes);
    }

    // ── lifecycle ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void suspend(UUID publicId) {
        Tenant tenant = requireLive(publicId);
        tenant.setStatus(TenantStatus.SUSPENDED);
        tenantRepository.save(tenant);
        audit(PlatformAuditAction.TENANT_SUSPEND, tenant,
                "Suspended tenant " + tenant.getOrganizationName());
    }

    @Override
    @Transactional
    public void reactivate(UUID publicId) {
        Tenant tenant = requireLive(publicId);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenantRepository.save(tenant);
        audit(PlatformAuditAction.TENANT_REACTIVATE, tenant,
                "Reactivated tenant " + tenant.getOrganizationName());
    }

    @Override
    @Transactional
    public void softDelete(UUID publicId) {
        Tenant tenant = requireLive(publicId);
        tenant.softDelete(currentActorEmail());
        tenantRepository.save(tenant);
        audit(PlatformAuditAction.TENANT_SOFT_DELETE, tenant,
                "Soft-deleted tenant " + tenant.getOrganizationName());
    }

    @Override
    @Transactional
    public void restore(UUID publicId) {
        Tenant tenant = tenantRepository.findByPublicId(publicId)
                .orElseThrow(() -> new TenantNotFoundException(publicId));
        if (!tenant.isDeleted()) {
            throw new BusinessException("Tenant is not deleted.", HttpStatus.BAD_REQUEST);
        }
        tenant.restore();
        tenantRepository.save(tenant);
        audit(PlatformAuditAction.TENANT_RESTORE, tenant,
                "Restored tenant " + tenant.getOrganizationName());
    }

    @Override
    @Transactional
    public void hardDelete(UUID publicId, String organizationCode) {
        Tenant tenant = tenantRepository.findByPublicId(publicId)
                .orElseThrow(() -> new TenantNotFoundException(publicId));

        // Danger zone: only from Trash, and only with the exact org code echoed back.
        if (!tenant.isDeleted()) {
            throw new BusinessException(
                    "Soft-delete the tenant first — a hard delete is only allowed from Trash.",
                    HttpStatus.BAD_REQUEST);
        }
        if (organizationCode == null
                || !organizationCode.trim().equalsIgnoreCase(tenant.getOrganizationCode())) {
            throw new BusinessException(
                    "Confirmation code does not match the organization code.", HttpStatus.BAD_REQUEST);
        }

        // Snapshot for the audit BEFORE the rows are gone.
        Long tenantId = tenant.getId();
        String code = tenant.getOrganizationCode();
        String name = tenant.getOrganizationName();
        UUID tenantPublicId = tenant.getPublicId();

        // Deregister: physically remove the tenant + its user accounts. Billing/audit snapshots are
        // retained by design; residual tenant-scoped rows are orphaned + unreachable (no tenant, no
        // login), left for a separate DB cleanup job.
        userRepository.deleteByTenantId(tenantId);
        tenantRepository.delete(tenant);

        platformAuditRecorder.safeRecord(PlatformAuditAction.TENANT_HARD_DELETE, true,
                tenantId, code, "TENANT", tenantPublicId,
                "HARD-DELETED tenant " + name + " (" + code + ") + its users; billing/audit retained");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Resolves a live (non-soft-deleted) tenant by publicId, or 404. */
    private Tenant requireLive(UUID publicId) {
        return tenantRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new TenantNotFoundException(publicId));
    }

    private void audit(PlatformAuditAction action, Tenant tenant, String description) {
        platformAuditRecorder.safeRecord(action, true,
                tenant.getId(), tenant.getOrganizationCode(),
                "TENANT", tenant.getPublicId(), description);
    }

    private String currentActorEmail() {
        PlatformActor actor = PlatformContext.getActor();
        return actor != null ? actor.email() : "system";
    }
}