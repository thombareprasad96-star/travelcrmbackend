package com.crm.travelcrm.platform.analytics.dto;

/** A named count — a chart slice/bar (status, plan, or a month's new-tenant count). */
public record LabelValue(String label, long value) {
}