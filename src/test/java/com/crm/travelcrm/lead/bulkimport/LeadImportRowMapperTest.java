package com.crm.travelcrm.lead.bulkimport;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Cell-level mapping rules. The theme is that a row is either usable or explained — the mapper never
 * guesses at a value that would end up on a real customer record.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeadImportRowMapperTest {

    private static final Long TENANT = 1L;

    @Mock
    private UserRepository userRepository;

    private LeadImportRowMapper mapper() {
        return new LeadImportRowMapper(userRepository);
    }

    /** A row carrying the four required columns, plus whatever the test adds. */
    private LeadImportSheet.LeadImportRow row(Map<LeadImportColumn, String> extra) {
        Map<LeadImportColumn, String> values = new LinkedHashMap<>();
        values.put(LeadImportColumn.CUSTOMER_NAME, "Ravi Sharma");
        values.put(LeadImportColumn.PHONE, "+919876543210");
        values.put(LeadImportColumn.LEAD_SOURCE, "Website");
        values.put(LeadImportColumn.LEAD_TYPE, "Fresh");
        values.putAll(extra);
        return new LeadImportSheet.LeadImportRow(2, values);
    }

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a minimal valid row maps cleanly and defaults the stage to New Lead")
    void minimalRowMaps() {
        LeadImportRowMapper.MappedRow mapped = mapper().map(row(Map.of()), TENANT);

        assertThat(mapped.isValid()).isTrue();
        assertThat(mapped.dto().getCustomerName()).isEqualTo("Ravi Sharma");
        assertThat(mapped.dto().getLeadType()).isEqualTo(LeadType.FRESH);
        assertThat(mapped.dto().getLeadStage()).isEqualTo(LeadStage.NEW_LEAD);
        // Blank owner is the normal case — assignForCreate decides.
        assertThat(mapped.dto().getAssignedUserId()).isNull();
    }

    // ── Phone ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("spreadsheet formatting is stripped from the phone so it passes the API's pattern")
    void phoneFormattingIsStripped() {
        LeadImportRowMapper.MappedRow mapped =
                mapper().map(row(Map.of(LeadImportColumn.PHONE, "+91 98765-43210")), TENANT);

        assertThat(mapped.isValid()).isTrue();
        assertThat(mapped.dto().getPhone()).isEqualTo("+919876543210");
    }

    @Test
    @DisplayName("a 00-prefixed international number becomes +, which is an unambiguous rewrite")
    void doubleZeroBecomesPlus() {
        LeadImportRowMapper.MappedRow mapped =
                mapper().map(row(Map.of(LeadImportColumn.PHONE, "0091 98765 43210")), TENANT);

        assertThat(mapped.dto().getPhone()).isEqualTo("+919876543210");
        assertThat(mapped.errors()).isEmpty();
    }

    @Test
    @DisplayName("a leading zero is explained, never silently given a country code")
    void leadingZeroIsReportedNotGuessed() {
        LeadImportRowMapper.MappedRow mapped =
                mapper().map(row(Map.of(LeadImportColumn.PHONE, "09876543210")), TENANT);

        assertThat(mapped.isValid()).isFalse();
        assertThat(mapped.errors()).anyMatch(e -> e.contains("starts with 0"));
    }

    // ── Enums ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an unknown lead type lists the allowed values instead of just failing")
    void unknownEnumListsAllowedValues() {
        LeadImportRowMapper.MappedRow mapped =
                mapper().map(row(Map.of(LeadImportColumn.LEAD_TYPE, "Lukewarm")), TENANT);

        assertThat(mapped.isValid()).isFalse();
        assertThat(mapped.errors()).anyMatch(e -> e.contains("Lukewarm") && e.contains("Fresh"));
    }

    @Test
    @DisplayName("a machine-only lead source cannot be asserted by hand")
    void machineOnlySourceIsRejected() {
        LeadImportRowMapper.MappedRow mapped =
                mapper().map(row(Map.of(LeadImportColumn.LEAD_SOURCE, "JustDial")), TENANT);

        assertThat(mapped.isValid()).isFalse();
        assertThat(mapped.errors()).anyMatch(e -> e.contains("cannot be set by hand"));
    }

    @Test
    @DisplayName("lead source accepts the enum name as well as the display name")
    void sourceAcceptsEitherVocabulary() {
        assertThat(mapper().map(row(Map.of(LeadImportColumn.LEAD_SOURCE, "SOCIAL_MEDIA")), TENANT)
                .isValid()).isTrue();
        assertThat(mapper().map(row(Map.of(LeadImportColumn.LEAD_SOURCE, "social media")), TENANT)
                .isValid()).isTrue();
    }

    // ── Dates and numbers ────────────────────────────────────────────────────

    @Test
    @DisplayName("dd/MM/yyyy is read the Indian way — 03/04/2026 is 3 April")
    void ddMmYyyyIsNotAmerican() {
        LeadImportRowMapper.MappedRow mapped =
                mapper().map(row(Map.of(LeadImportColumn.TRAVEL_DATE, "03/04/2026")), TENANT);

        assertThat(mapped.dto().getTravelDate()).isEqualTo(LocalDate.of(2026, 4, 3));
    }

    @Test
    @DisplayName("ISO dates are accepted too — that is what the Excel reader emits")
    void isoDatesAreAccepted() {
        LeadImportRowMapper.MappedRow mapped =
                mapper().map(row(Map.of(LeadImportColumn.TRAVEL_DATE, "2026-11-14")), TENANT);

        assertThat(mapped.dto().getTravelDate()).isEqualTo(LocalDate.of(2026, 11, 14));
    }

    @Test
    @DisplayName("an unparseable date names the accepted formats")
    void badDateIsExplained() {
        LeadImportRowMapper.MappedRow mapped =
                mapper().map(row(Map.of(LeadImportColumn.TRAVEL_DATE, "next friday")), TENANT);

        assertThat(mapped.isValid()).isFalse();
        assertThat(mapped.errors()).anyMatch(e -> e.contains("YYYY-MM-DD"));
    }

    @Test
    @DisplayName("currency symbols and separators are tolerated in the budget")
    void budgetIsCleaned() {
        LeadImportRowMapper.MappedRow mapped =
                mapper().map(row(Map.of(LeadImportColumn.BUDGET, "₹ 1,85,000")), TENANT);

        assertThat(mapped.dto().getBudget()).isEqualByComparingTo(new BigDecimal("185000"));
    }

    @Test
    @DisplayName("services are split on semicolons — commas would fight the CSV itself")
    void servicesSplitOnSemicolon() {
        LeadImportRowMapper.MappedRow mapped =
                mapper().map(row(Map.of(LeadImportColumn.SERVICES, "Hotel; Flight ;Visa")), TENANT);

        assertThat(mapped.dto().getServices()).containsExactly("Hotel", "Flight", "Visa");
    }

    // ── Owner resolution ─────────────────────────────────────────────────────

    @Test
    @DisplayName("an owner username resolves to that user's publicId, scoped to the tenant")
    void assigneeResolvesByUsername() {
        UUID publicId = UUID.randomUUID();
        User user = new User();
        user.setPublicId(publicId);
        when(userRepository.findByUsernameAndTenantIdAndDeletedAtIsNull(eq("priya.nair"), eq(TENANT)))
                .thenReturn(Optional.of(user));

        LeadImportRowMapper.MappedRow mapped =
                mapper().map(row(Map.of(LeadImportColumn.ASSIGNED_TO, "priya.nair")), TENANT);

        assertThat(mapped.isValid()).isTrue();
        assertThat(mapped.dto().getAssignedUserId()).isEqualTo(publicId);
    }

    @Test
    @DisplayName("an unknown username fails the row rather than quietly assigning it elsewhere")
    void unknownAssigneeFailsTheRow() {
        when(userRepository.findByUsernameAndTenantIdAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.empty());

        LeadImportRowMapper.MappedRow mapped =
                mapper().map(row(Map.of(LeadImportColumn.ASSIGNED_TO, "ghost")), TENANT);

        assertThat(mapped.isValid()).isFalse();
        assertThat(mapped.errors()).anyMatch(e -> e.contains("ghost"));
    }

    // ── Error accumulation ───────────────────────────────────────────────────

    @Test
    @DisplayName("every problem in a row is reported at once, not one per upload")
    void allErrorsAreCollected() {
        Map<LeadImportColumn, String> broken = new LinkedHashMap<>();
        broken.put(LeadImportColumn.CUSTOMER_NAME, "");
        broken.put(LeadImportColumn.PHONE, "");
        broken.put(LeadImportColumn.LEAD_TYPE, "Nope");
        broken.put(LeadImportColumn.TRAVEL_DATE, "soon");

        LeadImportRowMapper.MappedRow mapped = mapper().map(row(broken), TENANT);

        assertThat(mapped.errors()).hasSizeGreaterThanOrEqualTo(4);
    }
}
