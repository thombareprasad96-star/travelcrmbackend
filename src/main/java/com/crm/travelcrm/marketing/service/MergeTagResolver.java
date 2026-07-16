package com.crm.travelcrm.marketing.service;

import com.crm.travelcrm.customer.entity.Customer;
import org.springframework.stereotype.Component;

/**
 * Substitutes {@code {{token}}} merge tags in a message template with a customer's values.
 * Unknown/empty tokens resolve to an empty string (never left as a raw {@code {{tag}}}).
 */
@Component
public class MergeTagResolver {

    public String resolve(String template, Customer c) {
        if (template == null) return "";
        return template
                .replace("{{name}}", nz(c.getName()))
                .replace("{{firstName}}", firstName(c.getName()))
                .replace("{{city}}", nz(c.getCity()))
                .replace("{{state}}", nz(c.getState()))
                .replace("{{loyaltyTier}}", c.getTier() != null ? c.getTier().getDisplayName() : "")
                .replace("{{customerCode}}", nz(c.getCustomerCode()))
                .replace("{{email}}", nz(c.getEmail()))
                .replace("{{phone}}", nz(c.getPhone()));
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    private String firstName(String name) {
        if (name == null || name.isBlank()) return "";
        return name.trim().split("\\s+")[0];
    }
}