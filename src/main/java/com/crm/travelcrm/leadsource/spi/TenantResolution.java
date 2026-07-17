package com.crm.travelcrm.leadsource.spi;

/**
 * How the framework works out which tenant an inbound payload belongs to.
 *
 * <p>Both modes obey owner decision 2: <b>the body never supplies a tenantId</b>. It may supply an
 * account KEY, and only as a lookup into a row an authenticated tenant created.
 */
public enum TenantResolution {

    /**
     * The opaque ingest token in the URL identifies the connection, and therefore the tenant, before
     * anything is parsed. The default, and the only mode Phase 1 ships.
     */
    TOKEN,

    /**
     * The provider posts to ONE app-wide URL and identifies the account in the body (Meta:
     * {@code entry[0].id} is the Facebook page id). There is no per-tenant URL to carry a token.
     *
     * <p>The apparent circularity — verify needs the row, the row needs the tenant, the tenant needs
     * the parse, the parse comes after verify — is broken by ORDERING, not by a new abstraction:
     * <ol>
     *   <li>HMAC over the raw bytes with the PLATFORM app secret (config, not per-tenant) — no row
     *       needed to verify;</li>
     *   <li>the pure {@code adapter.accountKey(in)} extracts one field;</li>
     *   <li>that key is looked up against {@code external_account_id}.</li>
     * </ol>
     * So the tenant is known after HMAC + one field extraction and still BEFORE the full parse — which
     * is why {@code LeadIngestEvent} can be a {@code BaseTenantEntity} with an explicit tenant stamp in
     * every case.
     */
    PROVIDER_ACCOUNT
}
