package com.crm.travelcrm.platform.ops.service;

/** Platform data exports (CSV). Every export is audited as {@code DATA_EXPORT}. */
public interface DataExportService {

    /** The tenant registry as CSV (RFC-4180 quoted). */
    String exportTenantsCsv();
}
