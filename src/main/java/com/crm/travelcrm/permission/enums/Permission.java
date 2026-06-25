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
    // High-privilege, irreversible: hard-deletes a lead (used by the cancel-booking flow).
    // Not in any role default — only TENANT_ADMIN holds it (via the resolver bypass) until granted.
    LEAD_PERMANENT_DELETE ("Leads",   "Permanently delete lead (irreversible)"),

    // ── Bookings ────────────────────────────────────────────────────────────
    BOOKING_READ   ("Bookings",       "View bookings"),
    BOOKING_CREATE ("Bookings",       "Create booking"),
    BOOKING_UPDATE ("Bookings",       "Edit booking"),
    BOOKING_CANCEL ("Bookings",       "Cancel booking"),
    BOOKING_DELETE ("Bookings",       "Delete booking"),

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

    // ── Master data (coarse: read vs manage) ────────────────────────────────
    MASTER_READ   ("Master Data",     "View master data"),
    MASTER_MANAGE ("Master Data",     "Create / edit / delete master data"),

    // ── User management (mirrors the existing USER_* authorities) ───────────
    USER_READ   ("User Management",   "View users"),
    USER_CREATE ("User Management",   "Create user"),
    USER_UPDATE ("User Management",   "Edit user / manage permissions"),
    USER_DELETE ("User Management",   "Delete user"),

    // ── Reports ─────────────────────────────────────────────────────────────
    REPORT_VIEW ("Reports",           "View reports"),

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
                    BOOKING_READ, BOOKING_CREATE, BOOKING_UPDATE, BOOKING_CANCEL, BOOKING_DELETE,
                    CUSTOMER_READ, CUSTOMER_CREATE, CUSTOMER_UPDATE, CUSTOMER_DELETE,
                    QUOTATION_READ, QUOTATION_CREATE, QUOTATION_UPDATE, QUOTATION_DELETE,
                    VENDOR_READ, VENDOR_CREATE, VENDOR_UPDATE,
                    REMINDER_READ, REMINDER_CREATE, REMINDER_UPDATE, REMINDER_DELETE,
                    MASTER_READ, MASTER_MANAGE, REPORT_VIEW, USER_READ);

            case TRAVEL_AGENT -> EnumSet.of(
                    LEAD_READ, LEAD_CREATE, LEAD_UPDATE,
                    BOOKING_READ, BOOKING_CREATE, BOOKING_UPDATE,
                    CUSTOMER_READ, CUSTOMER_CREATE, CUSTOMER_UPDATE,
                    QUOTATION_READ, QUOTATION_CREATE, QUOTATION_UPDATE,
                    VENDOR_READ,
                    REMINDER_READ, REMINDER_CREATE, REMINDER_UPDATE,
                    MASTER_READ, REPORT_VIEW);

            // STAFF is deny-by-default: a new STAFF user holds NO permissions until a
            // TENANT_ADMIN explicitly grants them. The empty set is the role fallback used
            // only until a saved per-user map exists (see EffectivePermissionResolver).
            case STAFF -> EnumSet.noneOf(Permission.class);

            case ACCOUNTANT -> EnumSet.of(
                    BOOKING_READ, BOOKING_UPDATE,
                    CUSTOMER_READ,
                    QUOTATION_READ,
                    VENDOR_READ, VENDOR_UPDATE,
                    REPORT_VIEW, MASTER_READ);
        };
    }

    /** Catalog grouped by module, declaration order preserved — for the FE catalog endpoint. */
    public static Map<String, List<Permission>> groupedByModule() {
        return Arrays.stream(values())
                .collect(Collectors.groupingBy(
                        Permission::getModule, LinkedHashMap::new, Collectors.toList()));
    }
}