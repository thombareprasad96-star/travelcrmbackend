package com.crm.travelcrm.lead.attribution.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * Where a lead was acquired from, at campaign grain. First-touch, 1:1 with a lead.
 *
 * <p><b>Package is {@code lead.attribution}, not {@code leadsource}</b>: attribution is a property of
 * the LEAD, and a SYSTEM-origin lead can carry it with no integration row at all.
 *
 * <p><b>(a) {@link #leadId} is a LOGICAL Long with NO DB foreign key. This is not optional.</b> A real
 * FK, with no inverse mapping and no {@code TrashableType} entry, means the nightly purge — a single
 * transactional loop over every {@code TrashableType} calling {@code em.remove(entity)} — hits a live
 * attribution row on a 31-day-old trashed inbound lead, Postgres throws, the transaction rolls back,
 * and <b>every</b> purge for that tenant (leads, customers, bookings, master data, fleet) silently
 * fails forever, because the same row is retried the next night. The unique index on {@code lead_id}
 * still enforces 1:1, so the logical FK costs nothing. Do NOT add this to {@code TrashableType} (it is
 * not user-trashable) and do NOT rely on {@code ON DELETE CASCADE}.
 *
 * <p><b>(b) A separate table rather than columns on {@code Lead}</b> — for the read path, not for
 * tidiness. The lead list fetches 100 rows in one call with an {@code @EntityGraph}, and would carry
 * ~10 extra columns × 100 rows on every render for fields the list never shows. Only
 * INTEGRATION-origin leads have attribution at all, so the columns would be overwhelmingly null.
 *
 * <p><b>(c) {@code Lead} must NOT map the inverse side.</b> A nullable inverse {@code @OneToOne}
 * cannot be proxied without bytecode enhancement, so Hibernate would fire an extra SELECT PER LEAD in
 * that 100-row list — re-creating the exact N+1 this table exists to avoid. The detail path fetches
 * explicitly by {@code leadId}.
 *
 * <p><b>(d) {@link #campaignName} is a String SNAPSHOT, never a {@code campaignId}, never an FK.</b>
 * {@code marketing.entity.Campaign} is an OUTBOUND broadcast to a segment; attributing an inbound lead
 * to it would corrupt its {@code sentCount}/{@code totalRecipients} and feed junk to the dispatch
 * scheduler. Independently correct anyway: the ad campaign lives in Meta/Google, not in our database.
 * We record what the provider told us, at the time it told us.
 *
 * <p><b>(e) First-touch, 1:1 — an ACCEPTED RISK.</b> Owner decision 1 treats a repeat contact as a
 * follow-up on an existing acquisition, not a new attributable one. Nothing is lost: the second
 * delivery's full payload lives in {@code lead_ingest_events}, keyed to the same lead. If multi-touch
 * is ever wanted the forward path is purely additive — add {@code touch_seq} and make the unique
 * {@code (lead_id, touch_seq)}.
 *
 * <p><b>Campaign and recording data live ONLY here.</b> {@code LeadLog.ingestEventId} points at the raw
 * log, which purges at 30 days — it must never be the sole path to attribution.
 */
@Entity
@Table(
        name = "lead_attributions",
        indexes = {
                @Index(name = "idx_lead_attr_tenant", columnList = "tenant_id")
                // uq_lead_attributions_lead (UNIQUE on lead_id) and the campaign-name reporting index
                // are partial/soft-delete-aware, so they live in db/indexes.sql.
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class LeadAttribution extends BaseTenantEntity {

    /** Logical FK to {@code leads.id}. UNIQUE (see class javadoc (a)). */
    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    /** A snapshot of the provider's campaign name — see class javadoc (d). */
    @Column(name = "campaign_name", length = 255)
    private String campaignName;

    @Column(name = "campaign_id", length = 128)
    private String campaignId;

    @Column(name = "ad_id", length = 128)
    private String adId;

    @Column(name = "adset_id", length = 128)
    private String adsetId;

    @Column(name = "form_id", length = 128)
    private String formId;

    @Column(name = "gclid", length = 255)
    private String gclid;

    @Column(name = "fbclid", length = 255)
    private String fbclid;

    @Column(name = "keyword", length = 255)
    private String keyword;

    @Column(name = "placement", length = 128)
    private String placement;

    /**
     * A telephony call recording. <b>A URL, never an uploaded body.</b>
     *
     * <p>Never server-side fetched by default: a payload-supplied URL that the server retrieves is
     * full SSRF (link-local metadata endpoints, internal admin ports, {@code file://}).
     */
    @Column(name = "recording_url", length = 1000)
    private String recordingUrl;

    /** Recording URLs from IVR providers are typically signed and short-lived. */
    @Column(name = "recording_url_expires_at")
    private LocalDateTime recordingUrlExpiresAt;

    /** The DID the caller reached — which of the tenant's numbers rang. */
    @Column(name = "caller_did", length = 32)
    private String callerDid;
}
