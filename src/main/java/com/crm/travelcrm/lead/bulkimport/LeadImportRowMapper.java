package com.crm.travelcrm.lead.bulkimport;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.customer.enums.CommunicationPreference;
import com.crm.travelcrm.lead.dto.CreateLeadRequestDto;
import com.crm.travelcrm.lead.enums.LeadSource;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadType;
import com.crm.travelcrm.lead.enums.SourceSelectability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Turns one spreadsheet row into a {@link CreateLeadRequestDto}, collecting <b>every</b> problem with
 * the row rather than throwing on the first one — a user fixing a 300-row file needs the whole list,
 * not one error per upload cycle.
 *
 * <p>Deliberately does no database writes and no lead logic: duplicates, quota and assignment all
 * belong to {@code LeadServiceImpl.createLead}, which the import calls per row. The only lookup here
 * is resolving an owner's username, because that has to become a {@code publicId} before the DTO can
 * carry it.
 */
@Component
@RequiredArgsConstructor
public class LeadImportRowMapper {

    private final UserRepository userRepository;

    /**
     * Accepted date inputs. {@code dd/MM/yyyy} is the Indian convention this product uses everywhere
     * else, so {@code 03/04/2026} is <b>3 April</b>, not 4 March. US-style {@code MM/dd/yyyy} is
     * deliberately NOT accepted: the two are indistinguishable for the first twelve days of a month,
     * and silently guessing wrong on a travel date is worse than rejecting the cell.
     */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,               // 2026-11-14
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("d-M-yyyy"));

    /** The DTO built from a row, plus everything wrong with it. Empty errors ⇒ ready to create. */
    public record MappedRow(CreateLeadRequestDto dto, List<String> errors) {
        public boolean isValid() {
            return errors.isEmpty();
        }
    }

    public MappedRow map(LeadImportSheet.LeadImportRow row, Long tenantId) {
        CreateLeadRequestDto dto = new CreateLeadRequestDto();
        List<String> errors = new ArrayList<>();

        // ── Required ─────────────────────────────────────────────────────────
        dto.setCustomerName(requireText(row, LeadImportColumn.CUSTOMER_NAME, errors));
        dto.setPhone(mapPhone(row, errors));
        dto.setLeadSource(mapSource(row, errors));
        dto.setLeadType(mapEnum(row, LeadImportColumn.LEAD_TYPE, LeadType::fromValue,
                displayNames(LeadType.values(), LeadType::getDisplayName), true, errors));

        // ── Defaulted ────────────────────────────────────────────────────────
        // A row being imported is a new lead; a blank stage is the common case, not an error.
        LeadStage stage = mapEnum(row, LeadImportColumn.LEAD_STAGE, LeadStage::fromValue,
                displayNames(LeadStage.values(), LeadStage::getDisplayName), false, errors);
        dto.setLeadStage(stage != null ? stage : LeadStage.NEW_LEAD);

        // ── Optional ─────────────────────────────────────────────────────────
        String email = row.get(LeadImportColumn.EMAIL);
        if (email != null) {
            // Lower-cased to match how the duplicate check compares stored emails.
            dto.setEmail(email.toLowerCase());
        }
        dto.setAssignedUserId(mapAssignee(row, tenantId, errors));
        dto.setBudget(mapBudget(row, errors));
        dto.setTravelDate(mapDate(row, LeadImportColumn.TRAVEL_DATE, errors));
        dto.setFollowUpDate(mapDate(row, LeadImportColumn.FOLLOW_UP_DATE, errors));
        dto.setBirthDate(mapDate(row, LeadImportColumn.BIRTH_DATE, errors));
        dto.setAnniversaryDate(mapDate(row, LeadImportColumn.ANNIVERSARY_DATE, errors));
        dto.setPackageType(row.get(LeadImportColumn.PACKAGE_TYPE));
        dto.setDepartCountry(row.get(LeadImportColumn.DEPART_COUNTRY));
        dto.setDepartCity(row.get(LeadImportColumn.DEPART_CITY));
        dto.setNotes(row.get(LeadImportColumn.NOTES));
        dto.setAdults(mapInt(row, LeadImportColumn.ADULTS, errors));
        dto.setChildren(mapInt(row, LeadImportColumn.CHILDREN, errors));
        dto.setInfants(mapInt(row, LeadImportColumn.INFANTS, errors));
        dto.setRooms(mapInt(row, LeadImportColumn.ROOMS, errors));
        dto.setServices(mapServices(row));
        dto.setPreferredCommunication(mapEnum(row, LeadImportColumn.PREFERRED_COMMUNICATION,
                CommunicationPreference::fromValue,
                displayNames(CommunicationPreference.values(), Enum::name), false, errors));

        return new MappedRow(dto, errors);
    }

    // ── Field mappers ────────────────────────────────────────────────────────

    private String requireText(LeadImportSheet.LeadImportRow row, LeadImportColumn column,
                               List<String> errors) {
        String value = row.get(column);
        if (value == null) {
            errors.add(column.getHeader() + " is required");
        }
        return value;
    }

    /**
     * Strips spaces, dashes and brackets so a spreadsheet's {@code "+91 98765 43210"} becomes the bare
     * string the API's {@code @Pattern} accepts. This is formatting removal, not interpretation — no
     * digit is added or dropped.
     *
     * <p>A leading zero is reported rather than silently rewritten: {@code 09876543210} is a valid
     * local number in several countries and guessing a country code would put a wrong number on a real
     * customer record. The preview shows this before anything is imported.
     */
    private String mapPhone(LeadImportSheet.LeadImportRow row, List<String> errors) {
        String raw = requireText(row, LeadImportColumn.PHONE, errors);
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[\\s()\\-.]", "");
        if (cleaned.startsWith("00")) {
            cleaned = "+" + cleaned.substring(2);       // 0091… → +91…, an unambiguous rewrite
        }
        if (cleaned.startsWith("0")) {
            errors.add("Phone \"" + raw + "\" starts with 0 — use the country code instead, "
                    + "e.g. +91" + cleaned.substring(1));
            return cleaned;
        }
        return cleaned;
    }

    /**
     * Lead Source additionally rejects values a human is not allowed to pick — a legacy or
     * machine-only source (JustDial, IVR) asserts an origin the import cannot vouch for.
     */
    private LeadSource mapSource(LeadImportSheet.LeadImportRow row, List<String> errors) {
        String raw = row.get(LeadImportColumn.LEAD_SOURCE);
        if (raw == null) {
            errors.add(LeadImportColumn.LEAD_SOURCE.getHeader() + " is required");
            return null;
        }
        LeadSource source;
        try {
            source = LeadSource.fromValue(raw);
        } catch (RuntimeException e) {
            errors.add(invalidValue(LeadImportColumn.LEAD_SOURCE, raw, selectableSources()));
            return null;
        }
        if (source.getSelectability() != SourceSelectability.MANUAL_SELECTABLE) {
            errors.add("Lead Source \"" + raw + "\" cannot be set by hand — it is reserved for the "
                    + "integration that produces it. Allowed: " + selectableSources());
            return null;
        }
        return source;
    }

    private <E> E mapEnum(LeadImportSheet.LeadImportRow row, LeadImportColumn column,
                          java.util.function.Function<String, E> parser, String allowed,
                          boolean required, List<String> errors) {
        String raw = row.get(column);
        if (raw == null) {
            if (required) {
                errors.add(column.getHeader() + " is required. Allowed: " + allowed);
            }
            return null;
        }
        try {
            return parser.apply(raw);
        } catch (RuntimeException e) {
            errors.add(invalidValue(column, raw, allowed));
            return null;
        }
    }

    /** Username → the owner's publicId, scoped to the caller's tenant. */
    private java.util.UUID mapAssignee(LeadImportSheet.LeadImportRow row, Long tenantId,
                                       List<String> errors) {
        String username = row.get(LeadImportColumn.ASSIGNED_TO);
        if (username == null) {
            return null;   // let assignForCreate decide — see LeadImportColumn.ASSIGNED_TO
        }
        Optional<User> user =
                userRepository.findByUsernameAndTenantIdAndDeletedAtIsNull(username, tenantId);
        if (user.isEmpty()) {
            errors.add("No active user with username \"" + username + "\" in this organisation");
            return null;
        }
        return user.get().getPublicId();
    }

    private BigDecimal mapBudget(LeadImportSheet.LeadImportRow row, List<String> errors) {
        String raw = row.get(LeadImportColumn.BUDGET);
        if (raw == null) {
            return null;
        }
        // Currency symbols and thousands separators are what a real spreadsheet contains.
        String cleaned = raw.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty()) {
            errors.add(invalidValue(LeadImportColumn.BUDGET, raw, "a number, e.g. 85000"));
            return null;
        }
        try {
            BigDecimal budget = new BigDecimal(cleaned);
            if (budget.signum() < 0) {
                errors.add("Budget cannot be negative");
                return null;
            }
            return budget;
        } catch (NumberFormatException e) {
            errors.add(invalidValue(LeadImportColumn.BUDGET, raw, "a number, e.g. 85000"));
            return null;
        }
    }

    private Integer mapInt(LeadImportSheet.LeadImportRow row, LeadImportColumn column,
                           List<String> errors) {
        String raw = row.get(column);
        if (raw == null) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 0) {
                errors.add(column.getHeader() + " cannot be negative");
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            errors.add(invalidValue(column, raw, "a whole number"));
            return null;
        }
    }

    private LocalDate mapDate(LeadImportSheet.LeadImportRow row, LeadImportColumn column,
                              List<String> errors) {
        String raw = row.get(column);
        if (raw == null) {
            return null;
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(raw, format);
            } catch (DateTimeParseException ignored) {
                // try the next accepted shape
            }
        }
        errors.add(invalidValue(column, raw, "a date as YYYY-MM-DD or DD/MM/YYYY"));
        return null;
    }

    private List<String> mapServices(LeadImportSheet.LeadImportRow row) {
        String raw = row.get(LeadImportColumn.SERVICES);
        if (raw == null) {
            return null;
        }
        List<String> services = Arrays.stream(raw.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return services.isEmpty() ? null : services;
    }

    // ── Message helpers ──────────────────────────────────────────────────────

    private String invalidValue(LeadImportColumn column, String raw, String allowed) {
        return column.getHeader() + " \"" + raw + "\" is not valid. Expected: " + allowed;
    }

    private <E> String displayNames(E[] values, java.util.function.Function<E, String> label) {
        return Arrays.stream(values).map(label).collect(Collectors.joining(", "));
    }

    /** Only the sources a human may choose — the list the error message should offer. */
    private String selectableSources() {
        return Arrays.stream(LeadSource.values())
                .filter(s -> s.getSelectability() == SourceSelectability.MANUAL_SELECTABLE)
                .map(LeadSource::getDisplayName)
                .collect(Collectors.joining(", "));
    }
}
