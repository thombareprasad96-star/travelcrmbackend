package com.crm.travelcrm.quotation.analytics;

/** Who viewed a public quotation weblink. */
public enum ViewerType {
    HOME,       // the tenant's own team members (IP in the tenant's staff-IP set)
    EXTERNAL    // an actual client
}