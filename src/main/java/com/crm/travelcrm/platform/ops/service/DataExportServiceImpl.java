package com.crm.travelcrm.platform.ops.service;

import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DataExportServiceImpl implements DataExportService {

    private static final String HEADER =
            "organizationCode,organizationName,email,phone,status,plan,maxUsers,maxLeads,"
                    + "activeUsers,subscriptionStartDate,subscriptionEndDate,createdAt";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PlatformAuditRecorder platformAuditRecorder;

    @Override
    @Transactional(readOnly = true)
    public String exportTenantsCsv() {
        List<Tenant> tenants = tenantRepository.findByDeletedAtIsNull();

        Map<Long, Long> userCounts = tenants.isEmpty()
                ? Map.of()
                : userRepository.countActiveGroupedByTenant(tenants.stream().map(Tenant::getId).toList())
                        .stream().collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));

        StringBuilder sb = new StringBuilder(HEADER).append("\r\n");
        tenants.stream()
                .sorted(Comparator.comparing(Tenant::getOrganizationCode,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .forEach(t -> sb
                        .append(csv(t.getOrganizationCode())).append(',')
                        .append(csv(t.getOrganizationName())).append(',')
                        .append(csv(t.getEmail())).append(',')
                        .append(csv(t.getPhone())).append(',')
                        .append(csv(t.getStatus())).append(',')
                        .append(csv(t.getPlan())).append(',')
                        .append(csv(t.getMaxUsers())).append(',')
                        .append(csv(t.getMaxLeads())).append(',')
                        .append(csv(userCounts.getOrDefault(t.getId(), 0L))).append(',')
                        .append(csv(t.getSubscriptionStartDate())).append(',')
                        .append(csv(t.getSubscriptionEndDate())).append(',')
                        .append(csv(t.getCreatedAt())).append("\r\n"));

        platformAuditRecorder.safeRecord(PlatformAuditAction.DATA_EXPORT, true,
                null, null, "EXPORT", null,
                "Exported tenant registry CSV (" + tenants.size() + " tenants)");

        return sb.toString();
    }

    /** RFC-4180 field quoting. */
    private static String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
