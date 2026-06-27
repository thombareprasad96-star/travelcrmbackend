package com.crm.travelcrm.trash;

import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.master.addon.Addon;
import com.crm.travelcrm.master.airline.Airline;
import com.crm.travelcrm.master.cruise.Cruise;
import com.crm.travelcrm.master.geography.entity.City;
import com.crm.travelcrm.master.geography.entity.Country;
import com.crm.travelcrm.master.geography.entity.Destination;
import com.crm.travelcrm.master.hotel.Hotel;
import com.crm.travelcrm.master.sightseeing.Sightseeing;
import com.crm.travelcrm.master.vehicle.VehicleEntity;
import com.crm.travelcrm.quotation.entity.Quotation;
import com.crm.travelcrm.reminder.entity.Reminder;
import com.crm.travelcrm.vendor.entity.Vendor;

import java.util.Arrays;

/**
 * Single registry of every entity that participates in the shared Trash → Restore →
 * Auto-Purge lifecycle. Adding a new trashable module = adding one enum constant here;
 * listing, restore, delete-now and the purge job all work off this registry generically
 * (see {@code TrashServiceImpl}), so there is no per-module reinvention.
 *
 * <p>All registered entities extend {@code BaseTenantEntity} — they share {@code publicId},
 * {@code tenantId} and the {@code deletedAt}/{@code deletedBy} soft-delete columns, which is
 * exactly what the generic JPQL in the service relies on.</p>
 *
 * <p><b>Declaration order matters for the hard-purge.</b> Constants are listed children-before-
 * parents so {@code TrashServiceImpl.purgeForCurrentTenant} removes referencing rows before the
 * rows they reference (real master FKs: Hotel/Sightseeing/Airline/Cruise/Addon → City →
 * Destination → Country). Combined with the referential guard — which only lets a parent be
 * trashed once its children already are — a parent always expires no earlier than its children,
 * so a single purge pass never trips a foreign-key constraint.</p>
 *
 * @param key    stable API token used in URLs / DTOs (never the internal Long id)
 * @param module human-friendly module label used to group the Trash listing
 */
public enum TrashableType {

    // ── Core CRM (cross-aggregate links are logical FKs only — no DB constraint) ──
    LEAD("LEAD", "Leads", Lead.class),
    CUSTOMER("CUSTOMER", "Customers", Customer.class),
    BOOKING("BOOKING", "Bookings", Booking.class),
    QUOTATION("QUOTATION", "Quotations", Quotation.class),

    // ── Business entities (referenced by nothing via FK) ──────────────────────────
    VENDOR("VENDOR", "Vendors", Vendor.class),
    REMINDER("REMINDER", "Reminders", Reminder.class),

    // ── Master data — leaf entities first (they reference City), then the geography
    //     hierarchy City → Destination → Country, so purge deletes children first. ──
    HOTEL("HOTEL", "Hotels", Hotel.class),
    AIRLINE("AIRLINE", "Airlines", Airline.class),
    CRUISE("CRUISE", "Cruises", Cruise.class),
    ADDON("ADDON", "Add-ons", Addon.class),
    SIGHTSEEING("SIGHTSEEING", "Sightseeing", Sightseeing.class),
    VEHICLE("VEHICLE", "Vehicles", VehicleEntity.class),
    CITY("CITY", "Cities", City.class),
    DESTINATION("DESTINATION", "Destinations", Destination.class),
    COUNTRY("COUNTRY", "Countries", Country.class);

    private final String key;
    private final String module;
    private final Class<?> entityClass;

    TrashableType(String key, String module, Class<?> entityClass) {
        this.key = key;
        this.module = module;
        this.entityClass = entityClass;
    }

    public String key() {
        return key;
    }

    public String module() {
        return module;
    }

    public Class<?> entityClass() {
        return entityClass;
    }

    /** The JPQL entity name (simple class name) used to build generic trash queries. */
    public String entityName() {
        return entityClass.getSimpleName();
    }

    /** Resolve an API token to a type, case-insensitively. Returns null if unknown. */
    public static TrashableType fromKey(String key) {
        if (key == null) return null;
        return Arrays.stream(values())
                .filter(t -> t.key.equalsIgnoreCase(key))
                .findFirst()
                .orElse(null);
    }
}
