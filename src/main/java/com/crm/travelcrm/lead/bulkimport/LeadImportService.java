package com.crm.travelcrm.lead.bulkimport;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.RestoreAvailableException;
import com.crm.travelcrm.lead.bulkimport.dto.LeadImportOutcome;
import com.crm.travelcrm.lead.bulkimport.dto.LeadImportReportDto;
import com.crm.travelcrm.lead.bulkimport.dto.LeadImportRowResultDto;
import com.crm.travelcrm.lead.dto.CreateLeadRequestDto;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadStageGroups;
import com.crm.travelcrm.lead.exception.DuplicateLeadException;
import com.crm.travelcrm.lead.exception.LeadQuotaExceededException;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.lead.service.LeadService;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bulk lead import: parse a CSV/Excel upload, report exactly what will happen, then create the good
 * rows.
 *
 * <p><b>This class is deliberately NOT {@code @Transactional}.</b> Each row is created through
 * {@link LeadService#createLead(CreateLeadRequestDto)}, a different bean, so Spring's proxy opens a
 * fresh transaction per row. That is the whole failure model: row 187 hitting a duplicate must not
 * roll back the 186 leads already imported, and a batch-wide transaction would also hold the lead-code
 * counter's pessimistic lock for the entire file, blocking every other user of the tenant from
 * creating a lead until the import finished.
 *
 * <p><b>It also owns no lead logic.</b> Quota, duplicate detection, lead-code generation, customer
 * matching, assignment, the claim window and notifications all live in {@code createLead}, and the
 * import calls it exactly as the create form does — so an imported lead is indistinguishable from a
 * typed one, and none of those rules can drift for imports specifically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeadImportService {

    private final LeadImportFileReaderResolver readerResolver;
    private final LeadImportRowMapper rowMapper;
    private final LeadService leadService;
    private final LeadRepository leadRepository;
    private final TenantRepository tenantRepository;
    private final Validator validator;

    /** Validates and reports, writing nothing. */
    public LeadImportReportDto preview(MultipartFile file) {
        return process(file, false);
    }

    /** Creates every row that would have shown as READY in the preview. */
    public LeadImportReportDto importLeads(MultipartFile file) {
        return process(file, true);
    }

    // ── Core ─────────────────────────────────────────────────────────────────

    private LeadImportReportDto process(MultipartFile file, boolean commit) {
        Long tenantId = requireTenantId();
        LeadImportSheet sheet = readerResolver.resolve(file).read(file);

        assertUsableSheet(sheet);

        List<LeadImportRowResultDto> results = new ArrayList<>();
        // Contacts already seen IN THIS FILE. Without it the first of two identical rows imports and
        // the second is reported as a duplicate of a lead the user never had — technically true and
        // completely baffling. Catching it here names the real cause.
        Set<String> seenContacts = new HashSet<>();
        Integer remainingQuota = remainingQuota(tenantId);

        // Flipped the moment the plan limit bites, so the remaining rows are reported as SKIPPED
        // rather than each one re-attempting a create that cannot succeed.
        boolean quotaExhausted = false;

        for (LeadImportSheet.LeadImportRow row : sheet.rows()) {
            LeadImportRowMapper.MappedRow mapped = rowMapper.map(row, tenantId);
            CreateLeadRequestDto dto = mapped.dto();

            List<String> problems = new ArrayList<>(mapped.errors());
            problems.addAll(beanValidationErrors(dto));

            if (!problems.isEmpty()) {
                results.add(result(row, dto, LeadImportOutcome.INVALID, problems));
                continue;
            }

            String duplicateInFile = duplicateWithinFile(dto, seenContacts);
            if (duplicateInFile != null) {
                results.add(result(row, dto, LeadImportOutcome.DUPLICATE, List.of(duplicateInFile)));
                continue;
            }

            if (!commit) {
                results.add(previewRow(row, dto, tenantId));
                continue;
            }

            if (quotaExhausted) {
                results.add(result(row, dto, LeadImportOutcome.SKIPPED,
                        List.of("Your plan's lead limit was reached earlier in this file.")));
                continue;
            }

            try {
                LeadResponseDto created = leadService.createLead(dto);
                results.add(LeadImportRowResultDto.builder()
                        .rowNumber(row.rowNumber())
                        .customerName(dto.getCustomerName())
                        .phone(dto.getPhone())
                        .outcome(LeadImportOutcome.IMPORTED)
                        .messages(List.of())
                        // LeadResponseDto exposes the publicId as `id` — it has no `publicId` getter.
                        .leadPublicId(created.getId())
                        .leadCode(created.getLeadCode())
                        .build());

            } catch (DuplicateLeadException e) {
                results.add(result(row, dto, LeadImportOutcome.DUPLICATE, List.of(e.getMessage())));

            } catch (RestoreAvailableException e) {
                // A trashed lead for this contact. Skipped rather than auto-restored: restoring
                // resurrects the old lead's stage, owner and history, which is not what "import this
                // new enquiry" means. The message points the user at the record.
                results.add(result(row, dto, LeadImportOutcome.DUPLICATE, List.of(e.getMessage())));

            } catch (LeadQuotaExceededException e) {
                quotaExhausted = true;
                results.add(result(row, dto, LeadImportOutcome.SKIPPED, List.of(e.getMessage())));

            } catch (RuntimeException e) {
                // One bad row must not end the import. Logged with the row number so an operator can
                // find it; the user gets a readable line in the report.
                log.warn("Lead import row {} failed | tenantId: {}", row.rowNumber(), tenantId, e);
                results.add(result(row, dto, LeadImportOutcome.SKIPPED,
                        List.of("This row could not be imported. " + safeMessage(e))));
            }
        }

        return report(commit, sheet, results, remainingQuota);
    }

    /** Preview verdict for a row that already parsed and validated cleanly. */
    private LeadImportRowResultDto previewRow(LeadImportSheet.LeadImportRow row,
                                              CreateLeadRequestDto dto, Long tenantId) {
        List<String> collisions = existingLeadCollisions(dto, tenantId);
        if (!collisions.isEmpty()) {
            return result(row, dto, LeadImportOutcome.DUPLICATE, collisions);
        }
        return result(row, dto, LeadImportOutcome.READY, List.of());
    }

    // ── Checks ───────────────────────────────────────────────────────────────

    /** Whole-file problems: no usable rows, a missing required column, or more rows than we accept. */
    private void assertUsableSheet(LeadImportSheet sheet) {
        List<String> missing = java.util.Arrays.stream(LeadImportColumn.values())
                .filter(LeadImportColumn::isRequired)
                .filter(c -> !sheet.presentColumns().contains(c))
                .map(LeadImportColumn::getHeader)
                .toList();

        if (!missing.isEmpty()) {
            throw new BusinessException(
                    "The file is missing required column(s): " + String.join(", ", missing)
                            + ". Download the template to see the expected headers.",
                    HttpStatus.BAD_REQUEST);
        }
        if (sheet.rows().isEmpty()) {
            throw new BusinessException("The file has a header row but no data rows.",
                    HttpStatus.BAD_REQUEST);
        }
        if (sheet.rows().size() > LeadImportFileReader.MAX_ROWS) {
            throw new BusinessException(
                    "This file has more than " + LeadImportFileReader.MAX_ROWS
                            + " rows. Split it into smaller files and import them one at a time.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * The DTO is built in code, so {@code @Valid} never runs on it — the same gap that lets machine
     * ingest persist leads the edit form then refuses to save. Running the constraints here keeps an
     * imported lead editable afterwards.
     *
     * <p>{@code assignedUserId} is excluded on purpose: it is {@code @NotNull} on the DTO for the
     * create form, but a blank owner column is the normal case for an import and
     * {@code assignForCreate} resolves it (load-balanced for an admin, self for everyone else).
     */
    private List<String> beanValidationErrors(CreateLeadRequestDto dto) {
        return validator.validate(dto).stream()
                .filter(v -> !"assignedUserId".equals(v.getPropertyPath().toString()))
                .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .map(ConstraintViolation::getMessage)
                .distinct()
                .collect(Collectors.toList());
    }

    /** Records this row's contacts and returns a message if the file already used one of them. */
    private String duplicateWithinFile(CreateLeadRequestDto dto, Set<String> seenContacts) {
        String phoneKey = dto.getPhone() == null ? null : "p:" + dto.getPhone();
        String emailKey = dto.getEmail() == null
                ? null : "e:" + dto.getEmail().toLowerCase(Locale.ROOT);

        if (phoneKey != null && seenContacts.contains(phoneKey)) {
            return "This file already has a row with phone " + dto.getPhone() + ".";
        }
        if (emailKey != null && seenContacts.contains(emailKey)) {
            return "This file already has a row with email " + dto.getEmail() + ".";
        }
        if (phoneKey != null) {
            seenContacts.add(phoneKey);
        }
        if (emailKey != null) {
            seenContacts.add(emailKey);
        }
        return null;
    }

    /**
     * Preview-side duplicate probe. Mirrors {@code LeadServiceImpl.validateNoDuplicates} and
     * {@code checkTrashedForRestore} exactly — same repository methods, same terminal-stage exclusion —
     * so the preview cannot promise a row that the commit will then reject.
     */
    private List<String> existingLeadCollisions(CreateLeadRequestDto dto, Long tenantId) {
        Set<LeadStage> terminal = LeadStageGroups.TERMINAL_STAGES;
        List<String> messages = new ArrayList<>();

        if (dto.getEmail() != null
                && leadRepository.existsByEmailAndTenantIdAndDeletedAtIsNullAndLeadStageNotIn(
                dto.getEmail().toLowerCase(Locale.ROOT), tenantId, terminal)) {
            messages.add("An open lead already exists with email: " + dto.getEmail());
        }
        if (dto.getPhone() != null
                && leadRepository.existsByPhoneAndTenantIdAndDeletedAtIsNullAndLeadStageNotIn(
                dto.getPhone(), tenantId, terminal)) {
            messages.add("An open lead already exists with phone: " + dto.getPhone());
        }
        if (!messages.isEmpty()) {
            return messages;
        }

        // Only reachable when no ACTIVE lead matched — same order the create path uses.
        if (dto.getEmail() != null
                && leadRepository.findFirstByEmailAndTenantIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(
                dto.getEmail().toLowerCase(Locale.ROOT), tenantId).isPresent()) {
            messages.add("A lead with email " + dto.getEmail()
                    + " is in Trash. Restore it instead of importing a duplicate.");
        } else if (dto.getPhone() != null
                && leadRepository.findFirstByPhoneAndTenantIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(
                dto.getPhone(), tenantId).isPresent()) {
            messages.add("A lead with phone " + dto.getPhone()
                    + " is in Trash. Restore it instead of importing a duplicate.");
        }
        return messages;
    }

    /** Leads the plan still allows, or null when unlimited. Read-only; the create path still enforces. */
    private Integer remainingQuota(Long tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        Integer maxLeads = tenant != null ? tenant.getMaxLeads() : null;
        if (maxLeads == null || maxLeads <= 0) {
            return null;
        }
        long used = leadRepository.countByTenantIdAndDeletedAtIsNull(tenantId);
        return (int) Math.max(0, maxLeads - used);
    }

    // ── Assembly ─────────────────────────────────────────────────────────────

    private LeadImportRowResultDto result(LeadImportSheet.LeadImportRow row,
                                          CreateLeadRequestDto dto,
                                          LeadImportOutcome outcome,
                                          List<String> messages) {
        return LeadImportRowResultDto.builder()
                .rowNumber(row.rowNumber())
                // Fall back to the raw cell: an INVALID row may have failed precisely because the
                // name was missing, and an unlabelled row is hard to find in a spreadsheet.
                .customerName(dto.getCustomerName() != null
                        ? dto.getCustomerName()
                        : row.get(LeadImportColumn.CUSTOMER_NAME))
                .phone(dto.getPhone() != null ? dto.getPhone() : row.get(LeadImportColumn.PHONE))
                .outcome(outcome)
                .messages(messages)
                .build();
    }

    private LeadImportReportDto report(boolean committed, LeadImportSheet sheet,
                                       List<LeadImportRowResultDto> rows, Integer remainingQuota) {
        return LeadImportReportDto.builder()
                .committed(committed)
                .totalRows(rows.size())
                .readyCount(count(rows, LeadImportOutcome.READY))
                .importedCount(count(rows, LeadImportOutcome.IMPORTED))
                .duplicateCount(count(rows, LeadImportOutcome.DUPLICATE))
                .invalidCount(count(rows, LeadImportOutcome.INVALID))
                .skippedCount(count(rows, LeadImportOutcome.SKIPPED))
                .ignoredHeaders(sheet.ignoredHeaders())
                .remainingQuota(remainingQuota)
                .rows(rows)
                .build();
    }

    private int count(List<LeadImportRowResultDto> rows, LeadImportOutcome outcome) {
        return (int) rows.stream().filter(r -> r.getOutcome() == outcome).count();
    }

    /** Never leak a raw stack message to the UI; keep BusinessException wording, generalise the rest. */
    private String safeMessage(RuntimeException e) {
        return e instanceof BusinessException ? e.getMessage() : "Please check this row and try again.";
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext is empty. Ensure JwtAuthFilter is running and the JWT carries a tenantId.");
        }
        return tenantId;
    }
}
