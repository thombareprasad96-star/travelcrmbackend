package com.crm.travelcrm.lead.enums;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Which origins produce a lead <i>nobody has taken yet</i> — the one classification the incoming-leads
 * screen, the new-lead alert and the whole claim window are built on. Kept out of {@link LeadOrigin}
 * for the same reason {@link LeadStageGroups} is kept out of {@link LeadStage}: the enum stays a pure
 * list of constants and this stays the single home for the rule.
 *
 * <p><b>Definition.</b> A lead is <i>inbound</i> when a machine put it there — an integration
 * ({@code INTEGRATION}) or an internal producer with no connector row behind it ({@code SYSTEM}: the
 * sub-agent portal, repeat-customer automation). Everything a human typed into the CRM
 * ({@code MANUAL}) is excluded.
 *
 * <p><b>Why MANUAL is excluded.</b> Creating a lead in the CRM already assigns it — to the creator
 * for an ordinary agent, or to whoever a manager explicitly picked. There is nothing to race for, so
 * a claim window over it is meaningless and a tenant-wide "new lead!" alert would be telling five
 * colleagues about a sixth colleague's typing, with the customer's phone number attached. The claim
 * feed exists for the enquiry that arrives with no owner and a countdown running.
 *
 * <p><b>Null is not inbound.</b> {@code Lead.origin} is nullable in the entity (a {@code ddl-auto}
 * artefact — see the field's javadoc) and legacy rows are backfilled to MANUAL. Treating an unknown
 * origin as MANUAL is the fail-closed answer: an unclassified lead never gets broadcast, and never
 * becomes claimable by someone who does not already have row-scope on it.
 */
public final class LeadOriginGroups {

    private LeadOriginGroups() {}

    /**
     * Machine-made origins — the leads that arrive unowned and are therefore alerted, listed in the
     * claim feed, SLA-measured and claimable. Immutable.
     */
    public static final Set<LeadOrigin> INBOUND_ORIGINS = Collections.unmodifiableSet(
            EnumSet.of(LeadOrigin.INTEGRATION, LeadOrigin.SYSTEM));

    /** True when the lead arrived from a machine and so has no owner anyone chose. Null ⇒ false. */
    public static boolean isInbound(LeadOrigin origin) {
        return origin != null && INBOUND_ORIGINS.contains(origin);
    }
}
