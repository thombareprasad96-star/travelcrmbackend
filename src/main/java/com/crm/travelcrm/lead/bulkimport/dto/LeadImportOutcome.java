package com.crm.travelcrm.lead.bulkimport.dto;

/**
 * What happened (preview: <em>would</em> happen) to one row. Kept as a single vocabulary across both
 * endpoints so the frontend renders one table component for the preview and the result.
 */
public enum LeadImportOutcome {

    /** Preview only: the row parsed and validated cleanly and would be created. */
    READY,

    /** The row has a cell the import cannot use. Never created. */
    INVALID,

    /**
     * An open lead already exists for this phone/email, or a trashed one is waiting to be restored,
     * or the same contact appears twice in this file. Skipped, never overwritten.
     */
    DUPLICATE,

    /** Commit only: the lead was created. */
    IMPORTED,

    /**
     * Commit only: the row was valid but could not be created — the plan's lead limit was reached
     * part-way through, or the create failed unexpectedly.
     */
    SKIPPED
}
