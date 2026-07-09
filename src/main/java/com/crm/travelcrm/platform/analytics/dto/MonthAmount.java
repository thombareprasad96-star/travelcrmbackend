package com.crm.travelcrm.platform.analytics.dto;

import java.math.BigDecimal;

/** A month's monetary total — a point on the revenue series. */
public record MonthAmount(String month, BigDecimal amount) {
}