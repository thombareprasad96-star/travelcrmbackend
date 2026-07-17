package com.crm.travelcrm.lead.ingest;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.lead.enums.LeadOrigin;

/**
 * Who is creating a lead. Lets one {@code createLead} implementation serve both the human path and
 * machine ingestion, instead of forking into a second create path that drifts on quota, dedup and
 * assignment within a release or two.
 *
 * <p><b>This is a privilege primitive.</b> A public {@code createLead(dto, actor, policy)} means the
 * caller declares who is acting, so the only thing standing between an arbitrary caller and "create
 * a lead as somebody else" is that a {@code LeadActor} cannot be fabricated: the constructor is
 * private and the three factories below are the sole way to obtain one. {@link #human(User)} takes a
 * real authenticated {@link User} — it cannot invent one. Keep it that way; a public constructor
 * here silently turns an ordinary method call into an impersonation.
 *
 * <p>Not a {@code record}: a record's canonical constructor cannot be less accessible than the
 * record itself, and this type must be public (it appears in {@code LeadService}'s signature) while
 * its constructor must not be.
 *
 * <p>Carries the <b>internal {@code Long}</b> integration id, not a {@code publicId}. The ingest
 * gateway resolves the {@code LeadSourceIntegration} row by token before the tenant is even known,
 * so the id is tenant-correct by construction and a publicId round-trip would buy a redundant
 * lookup and nothing else. {@code publicId} still governs every API surface — a {@code LeadActor}
 * never crosses one.
 */
public final class LeadActor {

    private final Long userId;
    private final String displayName;
    private final LeadOrigin origin;
    private final Long integrationId;

    private LeadActor(Long userId, String displayName, LeadOrigin origin, Long integrationId) {
        this.userId = userId;
        this.displayName = displayName;
        this.origin = origin;
        this.integrationId = integrationId;
    }

    /** An authenticated staff user creating a lead through the UI. */
    public static LeadActor human(User user) {
        if (user == null) {
            throw new IllegalArgumentException("LeadActor.human requires an authenticated user");
        }
        return new LeadActor(user.getId(), user.getName(), LeadOrigin.MANUAL, null);
    }

    /**
     * A lead-source integration delivering an inbound lead.
     *
     * @param integrationId internal id of the {@code LeadSourceIntegration} row the ingest token
     *                      resolved to — never taken from a request body
     * @param label         the connection's tenant-facing label, for the audit trail
     */
    public static LeadActor integration(Long integrationId, String label) {
        if (integrationId == null) {
            throw new IllegalArgumentException("LeadActor.integration requires an integration id");
        }
        return new LeadActor(null, label, LeadOrigin.INTEGRATION, integrationId);
    }

    /**
     * Internal automation with no integration row behind it (sub-agent portal, repeat-customer
     * drip). {@code reason} is what lands in the audit trail, so make it legible to a human reading
     * it six months later.
     */
    public static LeadActor system(String reason) {
        return new LeadActor(null, reason, LeadOrigin.SYSTEM, null);
    }

    /** Null for INTEGRATION and SYSTEM — there is no user behind them. */
    public Long userId() {
        return userId;
    }

    /** The user's name, the connection's label, or the system reason. Never null in practice. */
    public String displayName() {
        return displayName;
    }

    public LeadOrigin origin() {
        return origin;
    }

    /** Non-null only for INTEGRATION. */
    public Long integrationId() {
        return integrationId;
    }

    /** True when a real user is behind this actor — i.e. the interactive path. */
    public boolean isHuman() {
        return origin == LeadOrigin.MANUAL;
    }

    @Override
    public String toString() {
        return "LeadActor[" + origin + " " + displayName
                + (integrationId == null ? "" : " integration=" + integrationId) + "]";
    }
}