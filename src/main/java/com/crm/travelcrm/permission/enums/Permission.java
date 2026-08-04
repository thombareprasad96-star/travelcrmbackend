package com.crm.travelcrm.permission.enums;

import com.crm.travelcrm.auth.enums.Role;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The single source of truth for fine-grained permission keys.
 *
 * Each constant's {@code name()} is the authority string checked by
 * {@code @PreAuthorize("hasAuthority('LEAD_CREATE')")} and stored as a key in the
 * per-user permission map ({@code user_permissions.permissions_json}). The frontend
 * renders this same catalog via {@code GET /api/permissions/catalog}, so the FE and BE
 * can never drift.
 *
 * Legacy coarse authorities (CRM_FULL, PLATFORM_ADMIN) are intentionally NOT listed
 * here — they are not per-user toggles; they stay in {@link Role#authorities()} until
 * controllers are migrated to fine-grained keys.
 */
public enum Permission {

    // ── Leads ───────────────────────────────────────────────────────────────
    LEAD_READ      ("Leads",          "View leads"),
    LEAD_CREATE    ("Leads",          "Create lead"),
    LEAD_UPDATE    ("Leads",          "Edit lead"),
    LEAD_DELETE    ("Leads",          "Delete lead"),
    // High-privilege gate for the "remove lead" option when cancelling a booking. Despite the
    // name it now moves the lead to Trash (recoverable), not a hard delete — only Trash delete-now
    // and the 30-day auto-purge remove it physically. Not in any role default; only TENANT_ADMIN
    // holds it (via the resolver bypass) until granted.
    LEAD_PERMANENT_DELETE ("Leads",   "Remove lead when cancelling a booking (moves to Trash)"),
    /**
     * Take ownership of a new lead that is still open (nobody has marked it contacted yet),
     * overriding whoever the auto-assignment gave it to.
     *
     * <p>Separate from {@code LEAD_UPDATE} on purpose: editing a lead and taking it off a colleague
     * are different acts, and an agency that wants its juniors to work leads without letting them
     * grab their seniors' has no way to express that if the two share a key. It is also the gate
     * that stops a read-only viewer from acquiring ownership.
     *
     * <p>Granted to MANAGER and TRAVEL_AGENT by default — the roles that actually work the pipeline.
     * NOT to SUB_AGENT: {@code AssignableUserResolver} excludes sub-agents from ever owning a parent
     * tenant's lead, and a claim permission would be a second door into exactly what that excludes.
     * STAFF is deny-by-default as always.
     */
    LEAD_CLAIM     ("Leads",          "Claim a new lead / override its assigned owner"),
    /**
     * Move a lead to a different owner AFTER it has been locked by first contact, and reopen a
     * claim window closed by mistake. The supervisory half of ownership.
     *
     * <p>MANAGER and TENANT_ADMIN only. Once a lead is contacted the customer has been spoken to,
     * and silently moving it (or reopening it so someone else can take it) is a management decision,
     * not a self-service one — which is the whole point of the contacted lock.
     */
    LEAD_REASSIGN_LOCKED ("Leads",    "Reassign or reopen a lead after it has been contacted"),

    // ── Bookings ────────────────────────────────────────────────────────────
    BOOKING_READ   ("Bookings",       "View bookings"),
    BOOKING_CREATE ("Bookings",       "Create booking"),
    BOOKING_UPDATE ("Bookings",       "Edit booking"),
    BOOKING_CANCEL ("Bookings",       "Cancel booking"),
    BOOKING_DELETE ("Bookings",       "Delete booking"),
    BOOKING_PROFIT_READ ("Bookings",  "View supplier costs, expenses and booking profit"),
    // High-privilege financial gate: disburse a refund AND override/waive a computed cancellation
    // charge. Like LEAD_PERMANENT_DELETE it is in no role default — only TENANT_ADMIN holds it (via
    // the resolver bypass) until explicitly granted, since it moves money and overrides the books.
    BOOKING_REFUND ("Bookings",       "Refund a booking / override cancellation charges"),
    // Manage the tenant's cancellation-charge policies (the tiered slabs + tax switches). Config,
    // not a per-booking action — TENANT_ADMIN by default (via bypass) until granted to others.
    CANCELLATION_POLICY_MANAGE ("Bookings", "Manage cancellation policies"),

    // ── Customers ───────────────────────────────────────────────────────────
    CUSTOMER_READ   ("Customers",     "View customers"),
    CUSTOMER_CREATE ("Customers",     "Create customer"),
    CUSTOMER_UPDATE ("Customers",     "Edit customer"),
    CUSTOMER_DELETE ("Customers",     "Delete customer"),

    // ── Quotations ──────────────────────────────────────────────────────────
    QUOTATION_READ   ("Quotations",   "View quotations"),
    QUOTATION_CREATE ("Quotations",   "Create quotation"),
    QUOTATION_UPDATE ("Quotations",   "Edit quotation"),
    QUOTATION_DELETE ("Quotations",   "Delete quotation"),

    // ── Vendors ─────────────────────────────────────────────────────────────
    VENDOR_READ   ("Vendors",         "View vendors"),
    VENDOR_CREATE ("Vendors",         "Create vendor"),
    VENDOR_UPDATE ("Vendors",         "Edit vendor"),
    VENDOR_DELETE ("Vendors",         "Delete vendor"),

    // ── Reminders ───────────────────────────────────────────────────────────
    REMINDER_READ   ("Reminders",     "View reminders"),
    REMINDER_CREATE ("Reminders",     "Create reminder"),
    REMINDER_UPDATE ("Reminders",     "Edit reminder"),
    REMINDER_DELETE ("Reminders",     "Delete reminder"),

    // ── Tasks & Calendar ────────────────────────────────────────────────────
    // Also gates the unified calendar feed (GET /api/calendar). The tenant-wide task
    // stats/workload and the calendar summary are additionally gated on CRM_FULL (blocks sub-agents).
    TASK_READ   ("Tasks",             "View tasks & calendar"),
    TASK_CREATE ("Tasks",             "Create task / calendar event"),
    TASK_UPDATE ("Tasks",             "Edit task / move on board"),
    TASK_DELETE ("Tasks",             "Delete task"),

    // ── Fleet / Vehicle Diary ───────────────────────────────────────────────
    FLEET_READ   ("Fleet",            "View fleet vehicles, drivers, trips & logs"),
    FLEET_CREATE ("Fleet",            "Add fleet vehicles, drivers, trips & logs"),
    FLEET_UPDATE ("Fleet",            "Edit fleet records / trip lifecycle (start, close, cancel)"),
    FLEET_DELETE ("Fleet",            "Delete fleet records"),

    // Fleet MONEY is a separate authority from fleet operations: a dispatcher creates trips and
    // records costs all day without ever being able to settle a driver's cash or close a period.
    // Deliberately THREE keys, not the ten the standalone plan proposed — recording a cost is part
    // of the trip diary (FLEET_CREATE/UPDATE), and approval happens ONCE on the trip settlement
    // rather than per expense row. See docs/FLEET_MODULE_REDESIGN.md §4.3.
    FLEET_MONEY_READ   ("Fleet",      "View fleet expenses, driver cash balances & settlements"),
    FLEET_MONEY_SETTLE ("Fleet",      "Settle a trip: approve costs, reconcile driver cash, record payout"),
    FLEET_PERIOD_CLOSE ("Fleet",      "Lock a fleet accounting period (financial year / month)"),

    // ── Master data (coarse: read vs manage) ────────────────────────────────
    MASTER_READ   ("Master Data",     "View master data"),
    MASTER_MANAGE ("Master Data",     "Create / edit / delete master data"),

    // ── Hotel marketplace (the tenant SIDE of the platform catalog) ─────────
    // Separate from MASTER_*: importing a platform hotel writes into the Hotel Master, but browsing
    // a catalog the platform curates is a different act from maintaining your own master data, and a
    // tenant can hold one without the other. Managing the catalog itself is not a tenant permission
    // at all — it lives in the SuperAdmin realm behind ROLE_SUPER_ADMIN.
    HOTEL_MARKETPLACE_VIEW        ("Platform Hotel", "Search the platform hotel catalog"),
    HOTEL_MARKETPLACE_SYNC_MASTER ("Platform Hotel", "Import a platform hotel into your hotel master"),
    HOTEL_MARKETPLACE_BOOK        ("Platform Hotel", "Send a hotel booking request to the platform"),
    /**
     * Withdraw a pending request, or ask the platform to cancel a confirmed one.
     *
     * <p>Granted to every role that holds {@code HOTEL_MARKETPLACE_BOOK}, deliberately: whoever can
     * create the liability must be able to start unwinding it, and cancellation is the mitigating
     * act, not the risky one. The irreversible half — deciding the supplier's cancellation charge and
     * settling the refund — is a SuperAdmin action behind step-up MFA, so the tenant permission only
     * ever opens a conversation. (Contrast {@code FLEET_MONEY_READ}, withheld from sales roles
     * because settling driver cash freezes immediately with no second party in the loop.)</p>
     */
    HOTEL_MARKETPLACE_CANCEL      ("Platform Hotel", "Cancel or withdraw a platform hotel booking"),
    /**
     * See what the tenant owes the PLATFORM on a marketplace booking.
     *
     * <p>Separate from {@code BOOKING_PROFIT_READ} because the payable is not a margin — it is a
     * price the agent must know to quote their customer. Gating it behind profit would make the whole
     * feature unusable for exactly the role that places the orders, since TRAVEL_AGENT holds no
     * profit permission. It grants sight of marketplace expense rows ONLY; every other expense line,
     * and every margin figure, stays behind {@code BOOKING_PROFIT_READ}.</p>
     */
    MARKETPLACE_PAYABLE_READ      ("Platform Hotel", "View what you owe the platform for a platform hotel booking"),

    // ── User management (mirrors the existing USER_* authorities) ───────────
    USER_READ   ("User Management",   "View users"),
    USER_CREATE ("User Management",   "Create user"),
    USER_UPDATE ("User Management",   "Edit user / manage permissions"),
    USER_DELETE ("User Management",   "Delete user"),

    // ── Reports ─────────────────────────────────────────────────────────────
    REPORT_VIEW ("Reports",           "View reports"),

    // ── Marketing & Campaigns ────────────────────────────────────────────────
    // Segments, bulk WhatsApp/email campaigns, drip sequences, birthday/anniversary automations.
    // SEND is the money/outreach gate (dispatch a campaign, activate a drip, fire a test) —
    // separated from CREATE/UPDATE so a role can build content without being able to broadcast.
    MARKETING_READ   ("Marketing",    "View segments, campaigns, drips & automations"),
    MARKETING_CREATE ("Marketing",    "Create segments, campaigns & drip sequences"),
    MARKETING_UPDATE ("Marketing",    "Edit segments, campaigns, drips & automations"),
    MARKETING_DELETE ("Marketing",    "Delete segments, campaigns & drip sequences"),
    MARKETING_SEND   ("Marketing",    "Send campaigns, activate drips & send test messages"),

    // ── Trash / Recycle Bin ──────────────────────────────────────────────────
    // View trashed records, restore them, and (most-restricted) permanently delete now.
    TRASH_VIEW    ("Trash",           "View trashed records"),
    TRASH_RESTORE ("Trash",           "Restore trashed records"),
    // Irreversible hard delete before the auto-purge window — TENANT_ADMIN only by default.
    TRASH_DELETE  ("Trash",           "Permanently delete a trashed record (irreversible)"),

    // ── Accounting / GST ──────────────────────────────────────────────────────
    // Finance data is tenant-wide (admin/accountant), never sub-agent row-scoped.
    ACCOUNTING_INVOICE_READ    ("Accounting", "View GST invoices & tax masters"),
    ACCOUNTING_INVOICE_MANAGE  ("Accounting", "Issue / cancel GST invoices"),
    ACCOUNTING_TDS_READ        ("Accounting", "View vendor bills, TDS & TCS"),
    ACCOUNTING_TDS_MANAGE      ("Accounting", "Manage vendor bills, payments & TDS/TCS"),
    ACCOUNTING_SETTINGS_MANAGE ("Accounting", "Manage GST settings & tax-rate masters"),

    // ── Communication Center ─────────────────────────────────────────────────
    // The unified inbox (WhatsApp, Email, SMS, Calls, Internal chat, Notes).
    //
    // COMM_READ carries the DATA SCOPE that implements the product's "all vs assigned
    // conversations" requirement: ScopeResolver reads own/team/all off this key and the service
    // filters comm_conversations.assigned_user_id by it. An unassigned thread stays visible at every
    // scope above NONE — a message from a new number belongs to nobody yet, and hiding it until
    // someone claims it means nobody ever does.
    COMM_READ     ("Communication",   "View conversations, messages and call history"),
    COMM_SEND     ("Communication",   "Send messages and reply on customer channels"),
    COMM_ASSIGN   ("Communication",   "Assign, snooze and close conversations"),
    COMM_CALL_LOG ("Communication",   "Log a call and set its outcome / follow-up"),
    COMM_CHAT     ("Communication",   "Use internal team chat"),

    // Privacy- and config-sensitive keys. Deliberately in NO role default — TENANT_ADMIN reaches
    // them through the resolver bypass, everyone else is granted them explicitly, following the
    // BOOKING_REFUND / LEAD_PERMANENT_DELETE precedent.
    //
    // COMM_NOTE_PRIVATE_READ is enforced as a QUERY predicate, not a mapper filter: a private note
    // must never be materialised inside a request that was not entitled to it.
    COMM_NOTE_PRIVATE_READ ("Communication", "Read other users' private notes"),
    COMM_RECORDING_READ    ("Communication", "Listen to call recordings"),
    COMM_TEMPLATE_MANAGE   ("Communication", "Create and edit message templates"),
    COMM_WORKFLOW_MANAGE   ("Communication", "Configure automated / scheduled messages"),

    // Tenant-wide analytics. Granted to MANAGER (and TENANT_ADMIN) only — the endpoints behind it
    // are additionally gated on CRM_FULL, which SUB_AGENT does not hold, so a franchise partner can
    // never read the parent agency's response times or agent leaderboard.
    COMM_REPORT_VIEW       ("Communication", "View communication reports & analytics"),

    // ── Settings ────────────────────────────────────────────────────────────
    SETTINGS_MANAGE ("Settings",      "Manage company settings");

    private final String module;
    private final String label;

    Permission(String module, String label) {
        this.module = module;
        this.label  = label;
    }

    public String getModule() { return module; }
    public String getLabel()  { return label; }
    /** The authority string (== enum name). */
    public String key()       { return name(); }

    /** True if {@code key} matches a known permission (used to validate saved maps). */
    public static boolean isValidKey(String key) {
        if (key == null) return false;
        for (Permission p : values()) {
            if (p.name().equals(key)) return true;
        }
        return false;
    }

    /**
     * Default permission set granted to a role before any per-user customization.
     * This seeds new users and is the fallback for users with no saved map yet.
     * TENANT_ADMIN gets every tenant permission (the admin bypass lives in the resolver);
     * SUPERADMIN is the PLATFORM owner and holds no tenant-level CRM permissions.
     */
    public static Set<Permission> defaultsFor(Role role) {
        return switch (role) {
            // Organization Admin — full control of its own tenant's CRM.
            case TENANT_ADMIN -> EnumSet.allOf(Permission.class);

            // Platform owner: manages tenants / global config (authority PLATFORM_ADMIN),
            // not a tenant CRM user, so NO tenant-level permissions. Superadmins are
            // SuperAdmin entities (not Users); this case exists only for exhaustiveness.
            case SUPERADMIN -> EnumSet.noneOf(Permission.class);

            case MANAGER -> EnumSet.of(
                    LEAD_READ, LEAD_CREATE, LEAD_UPDATE, LEAD_DELETE,
                    // Works the pipeline AND supervises it: claims open leads like any agent, and
                    // holds the post-lock reassign/reopen authority agents deliberately do not.
                    // Mirrored by the V2 PART 18 backfill; LeadClaimPermissionDefaultsTest fails the
                    // build if the two grant paths ever drift.
                    LEAD_CLAIM, LEAD_REASSIGN_LOCKED,
                    BOOKING_READ, BOOKING_CREATE, BOOKING_UPDATE, BOOKING_CANCEL, BOOKING_DELETE,
                    BOOKING_PROFIT_READ,
                    CUSTOMER_READ, CUSTOMER_CREATE, CUSTOMER_UPDATE, CUSTOMER_DELETE,
                    QUOTATION_READ, QUOTATION_CREATE, QUOTATION_UPDATE, QUOTATION_DELETE,
                    VENDOR_READ, VENDOR_CREATE, VENDOR_UPDATE,
                    REMINDER_READ, REMINDER_CREATE, REMINDER_UPDATE, REMINDER_DELETE,
                    TASK_READ, TASK_CREATE, TASK_UPDATE, TASK_DELETE,
                    FLEET_READ, FLEET_CREATE, FLEET_UPDATE, FLEET_DELETE,
                    // Sees fleet money; does NOT settle it. Settling a driver's cash is an
                    // irreversible act (the settlement freezes), so it is granted deliberately,
                    // never inherited from managing the fleet. Mirrored exactly by the backfill in
                    // V2__lead_code.sql PART 6; FleetMoneyPermissionDefaultsTest fails the build if
                    // the two grant paths ever drift apart.
                    FLEET_MONEY_READ,
                    MASTER_READ, MASTER_MANAGE, REPORT_VIEW, USER_READ,
                    // Importing writes into the hotel master, so it tracks MASTER_MANAGE.
                    HOTEL_MARKETPLACE_VIEW, HOTEL_MARKETPLACE_SYNC_MASTER,
                    HOTEL_MARKETPLACE_BOOK, HOTEL_MARKETPLACE_CANCEL, MARKETPLACE_PAYABLE_READ,
                    ACCOUNTING_INVOICE_READ, ACCOUNTING_TDS_READ,
                    MARKETING_READ, MARKETING_CREATE, MARKETING_UPDATE, MARKETING_DELETE, MARKETING_SEND,
                    // Communication: the full operational surface plus analytics. NOT the four
                    // privacy/config keys — private notes, recordings, templates and workflow config
                    // are granted per user, exactly like BOOKING_REFUND. Mirrored by the V2 PART 17
                    // backfill; CommPermissionDefaultsTest fails the build if the two ever drift.
                    COMM_READ, COMM_SEND, COMM_ASSIGN, COMM_CALL_LOG, COMM_CHAT, COMM_REPORT_VIEW,
                    TRASH_VIEW, TRASH_RESTORE);

            case TRAVEL_AGENT -> EnumSet.of(
                    LEAD_READ, LEAD_CREATE, LEAD_UPDATE,
                    // The role the broadcast-and-claim flow exists for. No LEAD_REASSIGN_LOCKED:
                    // once a colleague has spoken to the customer, taking the lead is a manager's
                    // call, not a peer's.
                    LEAD_CLAIM,
                    BOOKING_READ, BOOKING_CREATE, BOOKING_UPDATE,
                    CUSTOMER_READ, CUSTOMER_CREATE, CUSTOMER_UPDATE,
                    QUOTATION_READ, QUOTATION_CREATE, QUOTATION_UPDATE,
                    VENDOR_READ,
                    REMINDER_READ, REMINDER_CREATE, REMINDER_UPDATE,
                    TASK_READ, TASK_CREATE, TASK_UPDATE,
                    FLEET_READ, FLEET_CREATE, FLEET_UPDATE,
                    // Deliberately NO FLEET_MONEY_*: this is a sales role. It plans and runs trips,
                    // but tenant-wide driver cash positions, vendor rates and cost structure are not
                    // its business. Grant FLEET_MONEY_READ per-user if a particular agent needs it.
                    MASTER_READ, REPORT_VIEW,
                    // Browse the platform catalog and order from it, but no SYNC_MASTER: importing
                    // writes master data and this role only holds MASTER_READ. Grant that per-user.
                    // MARKETPLACE_PAYABLE_READ IS granted — the payable is a price this role has to
                    // know to quote a customer, not a margin, and TRAVEL_AGENT holds no profit
                    // permission at all, so without it the feature is unusable for its main user.
                    HOTEL_MARKETPLACE_VIEW, HOTEL_MARKETPLACE_BOOK, HOTEL_MARKETPLACE_CANCEL,
                    MARKETPLACE_PAYABLE_READ,
                    MARKETING_READ,
                    // Communication: this is the role that actually talks to customers all day, so
                    // it reads, sends and logs calls. No COMM_ASSIGN (triage is a supervisor act)
                    // and no COMM_REPORT_VIEW (tenant-wide response times are not its business).
                    COMM_READ, COMM_SEND, COMM_CALL_LOG, COMM_CHAT,
                    TRASH_VIEW);

            // STAFF is deny-by-default: a new STAFF user holds NO permissions until a
            // TENANT_ADMIN explicitly grants them. The empty set is the role fallback used
            // only until a saved per-user map exists (see EffectivePermissionResolver).
            case STAFF -> EnumSet.noneOf(Permission.class);

            case ACCOUNTANT -> EnumSet.of(
                    BOOKING_READ, BOOKING_UPDATE, BOOKING_PROFIT_READ,
                    CUSTOMER_READ,
                    QUOTATION_READ,
                    VENDOR_READ, VENDOR_UPDATE,
                    // The accountant is the settlement + period-close authority for fleet money
                    // (the compliance lens: "period close by financial year and month that I control").
                    FLEET_READ, FLEET_MONEY_READ, FLEET_MONEY_SETTLE, FLEET_PERIOD_CLOSE,
                    TASK_READ, TASK_CREATE, TASK_UPDATE,
                    REPORT_VIEW, MASTER_READ,
                    // Accounting is the accountant's core surface: full invoice + TDS/TCS + tax config.
                    ACCOUNTING_INVOICE_READ, ACCOUNTING_INVOICE_MANAGE,
                    ACCOUNTING_TDS_READ, ACCOUNTING_TDS_MANAGE, ACCOUNTING_SETTINGS_MANAGE,
                    // Communication: reads threads (a payment query arrives on WhatsApp like
                    // anything else) and uses team chat. Deliberately NOT COMM_SEND — this role
                    // does not correspond with customers on the agency's behalf.
                    COMM_READ, COMM_CHAT);

            // B2B franchise partner — its OWN sales pipeline only (row-scope OWN via ScopeResolver).
            // Soft-delete allowed on own leads/quotations; deliberately NO booking cancel/refund/delete,
            // NO customer delete, NO vendors (supplier cost/commission is parent-only), NO reports
            // (would leak tenant-wide revenue/profit), NO admin/settings/fleet/trash. Master data is
            // read-only so the sub-agent can pick the parent's hotels/vehicles when building a quote.
            case SUB_AGENT -> EnumSet.of(
                    LEAD_READ, LEAD_CREATE, LEAD_UPDATE, LEAD_DELETE,
                    QUOTATION_READ, QUOTATION_CREATE, QUOTATION_UPDATE, QUOTATION_DELETE,
                    BOOKING_READ, BOOKING_CREATE, BOOKING_UPDATE,
                    CUSTOMER_READ, CUSTOMER_CREATE, CUSTOMER_UPDATE,
                    REMINDER_READ, REMINDER_CREATE, REMINDER_UPDATE, REMINDER_DELETE,
                    TASK_READ, TASK_CREATE, TASK_UPDATE, TASK_DELETE,
                    MASTER_READ,
                    // Communication: its OWN conversations only. Row scope is doubly enforced —
                    // SubAgentScope confines it to threads it owns, and ScopeResolver defaults
                    // SUB_AGENT to OWN. No COMM_REPORT_VIEW: those endpoints are CRM_FULL-gated and
                    // SUB_AGENT holds no coarse authority at all, but stating it here keeps the
                    // catalogue honest rather than relying on a second gate.
                    COMM_READ, COMM_SEND, COMM_CHAT);
        };
    }

    /** Catalog grouped by module, declaration order preserved — for the FE catalog endpoint. */
    public static Map<String, List<Permission>> groupedByModule() {
        return Arrays.stream(values())
                .collect(Collectors.groupingBy(
                        Permission::getModule, LinkedHashMap::new, Collectors.toList()));
    }
}
