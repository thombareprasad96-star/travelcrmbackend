package com.crm.travelcrm.marketing.service;

import com.crm.travelcrm.customer.enums.CommunicationPreference;
import com.crm.travelcrm.customer.enums.CustomerStatus;
import com.crm.travelcrm.customer.enums.CustomerType;
import com.crm.travelcrm.customer.enums.LoyaltyTier;
import com.crm.travelcrm.marketing.dto.MergeTagDef;
import com.crm.travelcrm.marketing.dto.OptionDTO;
import com.crm.travelcrm.marketing.dto.SegmentFieldDef;
import com.crm.travelcrm.marketing.enums.SegmentFieldType;
import org.springframework.stereotype.Component;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The catalog of segmentable customer fields (with operators + enum options) and the merge
 * tags advertised to the composer. The single source of truth the query builder is driven by —
 * every field here must be handled by {@link SegmentEvaluator}.
 */
@Component
public class MarketingFieldCatalog {

    private static final List<String> ENUM_MULTI = List.of("IN", "NOT_IN", "EQUALS", "NOT_EQUALS");
    private static final List<String> ENUM_EQ    = List.of("EQUALS", "IN");

    public List<SegmentFieldDef> fields() {
        List<OptionDTO> tierOpts = Arrays.stream(LoyaltyTier.values())
                .map(t -> new OptionDTO(t.name(), t.getDisplayName())).toList();
        List<OptionDTO> typeOpts = Arrays.stream(CustomerType.values())
                .map(t -> new OptionDTO(t.name(), t.getDisplayName())).toList();
        List<OptionDTO> statusOpts = Arrays.stream(CustomerStatus.values())
                .map(t -> new OptionDTO(t.name(), t.getDisplayName())).toList();
        List<OptionDTO> commOpts = Arrays.stream(CommunicationPreference.values())
                .map(t -> new OptionDTO(t.name(), t.getDisplayName())).toList();
        List<OptionDTO> monthOpts = Arrays.stream(Month.values())
                .map(m -> new OptionDTO(m.getValue(), m.getDisplayName(TextStyle.FULL, Locale.ENGLISH))).toList();

        return List.of(
                new SegmentFieldDef("loyaltyTier", "Loyalty Tier", SegmentFieldType.ENUM, ENUM_MULTI, tierOpts),
                new SegmentFieldDef("customerType", "Customer Type", SegmentFieldType.ENUM, ENUM_MULTI, typeOpts),
                new SegmentFieldDef("status", "Status", SegmentFieldType.ENUM, ENUM_EQ, statusOpts),
                new SegmentFieldDef("commPref", "Preferred Channel", SegmentFieldType.ENUM, ENUM_EQ, commOpts),
                new SegmentFieldDef("city", "City", SegmentFieldType.TEXT, List.of("EQUALS", "NOT_EQUALS", "CONTAINS"), null),
                new SegmentFieldDef("state", "State", SegmentFieldType.TEXT, List.of("EQUALS", "CONTAINS"), null),
                new SegmentFieldDef("email", "Email", SegmentFieldType.TEXT, List.of("IS_SET", "IS_NOT_SET", "CONTAINS"), null),
                new SegmentFieldDef("birthdayMonth", "Birthday Month", SegmentFieldType.MONTH, List.of("IN", "EQUALS"), monthOpts),
                new SegmentFieldDef("anniversaryMonth", "Anniversary Month", SegmentFieldType.MONTH, List.of("IN", "EQUALS"), monthOpts),
                new SegmentFieldDef("createdAt", "Date Added", SegmentFieldType.DATE, List.of("BETWEEN", "GREATER_THAN", "LESS_THAN"), null)
        );
    }

    public List<MergeTagDef> mergeTags() {
        return List.of(
                new MergeTagDef("{{name}}", "Full name", "Rahul Sharma"),
                new MergeTagDef("{{firstName}}", "First name", "Rahul"),
                new MergeTagDef("{{city}}", "City", "Mumbai"),
                new MergeTagDef("{{state}}", "State", "Maharashtra"),
                new MergeTagDef("{{loyaltyTier}}", "Loyalty tier", "Gold"),
                new MergeTagDef("{{customerCode}}", "Customer code", "CUS10001"),
                new MergeTagDef("{{email}}", "Email", "rahul@example.com"),
                new MergeTagDef("{{phone}}", "Phone", "+91 98765 43210")
        );
    }
}