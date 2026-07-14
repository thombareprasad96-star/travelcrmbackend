package com.crm.travelcrm.common.seed;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.auth.repository.SuperAdminRepository;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.PaymentStatus;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.bookingreminder.entity.BookingReminder;
import com.crm.travelcrm.bookingreminder.entity.BookingReminderType;
import com.crm.travelcrm.bookingreminder.repository.BookingReminderRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.company.entity.Company;
import com.crm.travelcrm.company.entity.TaxRate;
import com.crm.travelcrm.company.repository.CompanyRepository;
import com.crm.travelcrm.company.repository.TaxRateRepository;
import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.repository.CustomerRepository;
import com.crm.travelcrm.fleet.entity.FleetDriver;
import com.crm.travelcrm.fleet.entity.FleetFuelLog;
import com.crm.travelcrm.fleet.entity.FleetMaintenanceLog;
import com.crm.travelcrm.fleet.entity.FleetTrip;
import com.crm.travelcrm.fleet.entity.FleetVehicle;
import com.crm.travelcrm.fleet.enums.FleetDriverStatus;
import com.crm.travelcrm.fleet.enums.FleetOwnerType;
import com.crm.travelcrm.fleet.enums.FleetTripStatus;
import com.crm.travelcrm.fleet.enums.FleetVehicleStatus;
import com.crm.travelcrm.fleet.repository.FleetDriverRepository;
import com.crm.travelcrm.fleet.repository.FleetFuelLogRepository;
import com.crm.travelcrm.fleet.repository.FleetMaintenanceLogRepository;
import com.crm.travelcrm.fleet.repository.FleetTripRepository;
import com.crm.travelcrm.fleet.repository.FleetVehicleRepository;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadItinerary;
import com.crm.travelcrm.lead.enums.LeadSource;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadType;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.quotationtemplate.entity.QuotationTemplate;
import com.crm.travelcrm.quotationtemplate.entity.QuotationTemplateHotel;
import com.crm.travelcrm.quotationtemplate.entity.QuotationTemplateItinerary;
import com.crm.travelcrm.quotationtemplate.repository.QuotationTemplateRepository;
import com.crm.travelcrm.master.addon.Addon;
import com.crm.travelcrm.master.addon.AddonRepository;
import com.crm.travelcrm.master.airline.Airline;
import com.crm.travelcrm.master.airline.AirlineRepository;
import com.crm.travelcrm.master.cruise.Cruise;
import com.crm.travelcrm.master.cruise.CruiseRepository;
import com.crm.travelcrm.master.cruise.CruiseRoomType;
import com.crm.travelcrm.master.cruise.CruiseRoomTypeRepository;
import com.crm.travelcrm.master.geography.entity.City;
import com.crm.travelcrm.master.geography.entity.Country;
import com.crm.travelcrm.master.geography.entity.Destination;
import com.crm.travelcrm.master.geography.repository.CityRepository;
import com.crm.travelcrm.master.geography.repository.CountryRepository;
import com.crm.travelcrm.master.geography.repository.DestinationRepository;
import com.crm.travelcrm.master.hotel.Hotel;
import com.crm.travelcrm.master.hotel.HotelRepository;
import com.crm.travelcrm.master.hotel.MealPlan;
import com.crm.travelcrm.master.hotel.MealPlanRepository;
import com.crm.travelcrm.master.hotel.RoomType;
import com.crm.travelcrm.master.hotel.RoomTypeRepository;
import com.crm.travelcrm.master.sightseeing.Sightseeing;
import com.crm.travelcrm.master.sightseeing.SightseeingRepository;
import com.crm.travelcrm.master.vehicle.VehicleEntity;
import com.crm.travelcrm.master.vehicle.VehicleRepository;
import com.crm.travelcrm.notificationsetting.entity.NotificationSetting;
import com.crm.travelcrm.notificationsetting.repository.NotificationSettingRepository;
import com.crm.travelcrm.permission.entity.PermissionTemplate;
import com.crm.travelcrm.permission.repository.PermissionTemplateRepository;
import com.crm.travelcrm.reminder.entity.Reminder;
import com.crm.travelcrm.reminder.entity.ReminderType;
import com.crm.travelcrm.reminder.repository.ReminderRepository;
import com.crm.travelcrm.platform.subscription.entity.Plan;
import com.crm.travelcrm.platform.subscription.repository.PlanRepository;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.enums.TenantPlan;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import com.crm.travelcrm.vendor.entity.Vendor;
import com.crm.travelcrm.vendor.repository.VendorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dev-only demo-data seeder. Inserts 5 rows into each major domain table on startup,
 * idempotently (a table is seeded only when it is empty), under one demo tenant.
 *
 * <p>Enabled via {@code app.seed.enabled=true} (off by default — never runs in prod
 * unless explicitly switched on). Tenant-scoped entities get their {@code tenant_id}
 * stamped by {@code TenantEntityListener}, so we set {@link TenantContext} to the demo
 * tenant for the duration of seeding and clear it in a finally block.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
public class DevDataSeeder implements CommandLineRunner {

    private static final int N = 5;
    private static final String PWD = "Password@123";


    private final PasswordEncoder passwordEncoder;
    private final SuperAdminRepository superAdminRepository;
    private final TenantRepository tenantRepository;
    private final PlanRepository planRepository;
    private final UserRepository userRepository;
    private final CountryRepository countryRepository;
    private final DestinationRepository destinationRepository;
    private final CityRepository cityRepository;
    private final HotelRepository hotelRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final MealPlanRepository mealPlanRepository;
    private final AirlineRepository airlineRepository;
    private final CruiseRepository cruiseRepository;
    private final CruiseRoomTypeRepository cruiseRoomTypeRepository;
    private final VehicleRepository vehicleRepository;
    private final AddonRepository addonRepository;
    private final SightseeingRepository sightseeingRepository;
    private final CustomerRepository customerRepository;
    private final VendorRepository vendorRepository;
    private final LeadRepository leadRepository;
    private final BookingRepository bookingRepository;
    private final ReminderRepository reminderRepository;
    private final BookingReminderRepository bookingReminderRepository;
    private final PermissionTemplateRepository permissionTemplateRepository;
    private final TaxRateRepository taxRateRepository;
    private final CompanyRepository companyRepository;
    private final NotificationSettingRepository notificationSettingRepository;
    private final FleetVehicleRepository fleetVehicleRepository;
    private final FleetDriverRepository fleetDriverRepository;
    private final FleetTripRepository fleetTripRepository;
    private final FleetFuelLogRepository fleetFuelLogRepository;
    private final FleetMaintenanceLogRepository fleetMaintenanceLogRepository;
    private final QuotationTemplateRepository quotationTemplateRepository;

    @Override
    public void run(String... args) {
        log.info("[DevDataSeeder] starting (app.seed.enabled=true)…");

        seedSuperAdmin();
        seedPlans();
        Tenant tenant = getOrCreateTenant();

        // Everything below is tenant-scoped — stamp tenant_id via TenantContext.
        TenantContext.setTenantId(tenant.getId());
        try {
            List<User> users = seedUsers(tenant.getId());
            List<Country> countries = seedCountries();
            List<Destination> destinations = seedDestinations(countries);
            List<City> cities = seedCities(countries, destinations);

            seedHotels(cities);
            seedAirlines(cities);
            seedCruises(cities);
            seedVehicles(cities);
            seedAddons(cities);
            seedSightseeings(cities);
            seedQuotationTemplates(cities);

            List<Customer> customers = seedCustomers();
            seedVendors();
            List<Lead> leads = seedLeads(users);
            seedBookings(customers, destinations, leads);
            seedFleet();
            seedReminders();
            seedBookingReminders();
            seedPermissionTemplates();
            seedTaxRates();
            seedCompany();
            seedNotificationSettings();
        } finally {
            TenantContext.clear();
        }

        log.info("[DevDataSeeder] done.");
    }

    // ── bootstrap ────────────────────────────────────────────────────────────

    private void seedSuperAdmin() {
        if (superAdminRepository.count() > 0) return;
        superAdminRepository.save(SuperAdmin.builder()
                .name("Platform Admin")
                .email("superadmin@demo.crm")
                .password(passwordEncoder.encode(PWD))
                .enabled(true)
                .build());
        log.info("[DevDataSeeder] seeded SuperAdmin (superadmin@demo.crm / {})", PWD);
    }

    private void seedPlans() {
        if (planRepository.count() > 0) return;
        planRepository.save(Plan.builder()
                .code(TenantPlan.STARTER).displayName("Basic")
                .monthlyPrice(new BigDecimal("2999")).currency("INR")
                .maxUsers(5).maxLeads(500)
                .maxBookingsPerMonth(50).maxStorageMb(512).maxSubAgents(0)
                .modules(new HashSet<>(Set.of("LEADS", "BOOKINGS", "QUOTATIONS", "CUSTOMERS", "MASTERS")))
                .active(true).build());
        planRepository.save(Plan.builder()
                .code(TenantPlan.PRO).displayName("Pro")
                .monthlyPrice(new BigDecimal("7999")).currency("INR")
                .maxUsers(20).maxLeads(5000)
                .maxBookingsPerMonth(500).maxStorageMb(5120).maxSubAgents(5)
                .modules(new HashSet<>(Set.of("LEADS", "BOOKINGS", "QUOTATIONS", "CUSTOMERS", "MASTERS",
                        "VENDORS", "REPORTS", "FLEET", "WHATSAPP")))
                .active(true).build());
        planRepository.save(Plan.builder()
                .code(TenantPlan.ENTERPRISE).displayName("Enterprise")
                .monthlyPrice(new BigDecimal("19999")).currency("INR")
                .maxUsers(null).maxLeads(null)
                .maxBookingsPerMonth(null).maxStorageMb(null).maxSubAgents(50)
                .modules(new HashSet<>(Set.of("LEADS", "BOOKINGS", "QUOTATIONS", "CUSTOMERS", "MASTERS",
                        "VENDORS", "REPORTS", "FLEET", "WHATSAPP", "DISHA_AI", "PORTAL")))
                .active(true).build());
        log.info("[DevDataSeeder] seeded 3 plans (Basic/Pro/Enterprise)");
    }

    private Tenant getOrCreateTenant() {
        return tenantRepository.findAll().stream().findFirst().orElseGet(() ->
                tenantRepository.save(Tenant.builder()
                        .organizationName("Demo Travels")
                        .organizationCode("DEMO")
                        .email("org@demo.crm")
                        .phone("+91 90000 00000")
                        .address("1 Demo Street, Demo City")
                        .subscriptionStartDate(LocalDate.now())
                        .subscriptionEndDate(LocalDate.now().plusYears(1))
                        .build()));
    }

    // ── users ────────────────────────────────────────────────────────────────

    private List<User> seedUsers(Long tenantId) {
        if (userRepository.findAllByTenantIdAndDeletedAtIsNull(tenantId).size() >= N) {
            return userRepository.findAllByTenantIdAndDeletedAtIsNull(tenantId);
        }
        Role[] roles = { Role.TENANT_ADMIN, Role.MANAGER, Role.TRAVEL_AGENT, Role.STAFF, Role.ACCOUNTANT };
        String[] names = { "Demo Admin", "Demo Manager", "Demo Agent", "Demo Staff", "Demo Accountant" };
        List<User> out = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            String email = roles[i].name().toLowerCase() + "@demo.crm";
            if (userRepository.findByEmailAndTenantId(email, tenantId).isPresent()) continue;
            out.add(userRepository.save(User.builder()
                    .name(names[i])
                    .email(email)
                    .password(passwordEncoder.encode(PWD))
                    .role(roles[i])
                    .tenantId(tenantId)
                    .phoneNumber("+91 90000 0000" + i)
                    .isActive(true)
                    .build()));
        }
        log.info("[DevDataSeeder] seeded {} users (e.g. tenant_admin@demo.crm / {})", out.size(), PWD);
        return out.isEmpty() ? userRepository.findAllByTenantIdAndDeletedAtIsNull(tenantId) : out;
    }

    // ── geography ────────────────────────────────────────────────────────────

    private List<Country> seedCountries() {
        if (countryRepository.count() > 0) return countryRepository.findAll();
        String[][] data = {
                {"India", "IN"}, {"United States", "US"}, {"United Arab Emirates", "AE"},
                {"Thailand", "TH"}, {"Singapore", "SG"}
        };
        List<Country> out = new ArrayList<>();
        for (String[] d : data) {
            out.add(countryRepository.save(Country.builder().name(d[0]).code(d[1])
                    .description(d[0] + " country").build()));
        }
        log.info("[DevDataSeeder] seeded {} countries", out.size());
        return out;
    }

    private List<Destination> seedDestinations(List<Country> countries) {
        if (destinationRepository.count() > 0) return destinationRepository.findAll();
        String[] names = { "Goa", "Manali", "Dubai", "Phuket", "Sentosa" };
        List<Destination> out = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            Country c = countries.get(i % countries.size());
            out.add(destinationRepository.save(Destination.builder()
                    .country(c).name(names[i]).type("Leisure").status("Active")
                    .price(BigDecimal.valueOf(25000L + i * 5000L))
                    .description(names[i] + " holiday destination").build()));
        }
        log.info("[DevDataSeeder] seeded {} destinations", out.size());
        return out;
    }

    private List<City> seedCities(List<Country> countries, List<Destination> destinations) {
        if (cityRepository.count() > 0) return cityRepository.findAll();
        String[] names = { "Panaji", "Shimla", "Dubai City", "Phuket Town", "Singapore City" };
        String[] codes = { "GOI", "SLV", "DXB", "HKT", "SIN" };
        List<City> out = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            Country c = countries.get(i % countries.size());
            Destination d = destinations.get(i % destinations.size());
            // Only link a city to a destination when both share the same country.
            Destination linked = d.getCountry() != null && d.getCountry().getId() == c.getId() ? d : null;
            out.add(cityRepository.save(City.builder()
                    .country(c).destination(linked).name(names[i]).code(codes[i])
                    .state("State " + (i + 1)).build()));
        }
        log.info("[DevDataSeeder] seeded {} cities", out.size());
        return out;
    }

    // ── masters ──────────────────────────────────────────────────────────────

    private void seedHotels(List<City> cities) {
        if (hotelRepository.count() > 0) return;
        for (int i = 0; i < N; i++) {
            City city = cities.get(i % cities.size());
            hotelRepository.save(Hotel.builder()
                    .city(city).name("Demo Hotel " + (i + 1))
                    .stars(3 + (i % 3)).rating(4.0 + (i % 5) / 10.0)
                    .address("Hotel address " + (i + 1)).contactPerson("Manager " + (i + 1))
                    .phone("+91 90000 100" + i).email("hotel" + i + "@demo.crm").build());
        }
        // 5 room types + 5 meal plans attached to the first hotel.
        Hotel first = hotelRepository.findAll().get(0);
        String[] roomNames = { "Standard", "Deluxe", "Suite", "Executive", "Family" };
        String[] mealNames = { "EP", "CP", "MAP", "AP", "AI" };
        for (int i = 0; i < N; i++) {
            roomTypeRepository.save(RoomType.builder()
                    .hotel(first).name(roomNames[i]).size((20 + i * 5) + " sqm")
                    .occupancy(2 + (i % 3)).bedType(i % 2 == 0 ? "King" : "Twin")
                    .description(roomNames[i] + " room").build());
            mealPlanRepository.save(MealPlan.builder()
                    .hotel(first).name(mealNames[i]).description(mealNames[i] + " plan")
                    .price(BigDecimal.valueOf(500L + i * 250L)).build());
        }
        log.info("[DevDataSeeder] seeded 5 hotels + 5 room types + 5 meal plans");
    }

    private void seedAirlines(List<City> cities) {
        if (airlineRepository.count() > 0) return;
        String[][] data = {
                {"IndiGo", "6E", "India"}, {"Air India", "AI", "India"},
                {"Emirates", "EK", "UAE"}, {"Thai Airways", "TG", "Thailand"},
                {"Singapore Airlines", "SQ", "Singapore"}
        };
        for (int i = 0; i < N; i++) {
            airlineRepository.save(Airline.builder()
                    .city(cities.get(i % cities.size())).name(data[i][0]).iata(data[i][1])
                    .country(data[i][2]).status("Active").fleet((50 + i * 10) + " aircraft").build());
        }
        log.info("[DevDataSeeder] seeded 5 airlines");
    }

    private void seedCruises(List<City> cities) {
        if (cruiseRepository.count() > 0) return;
        String[] names = { "Royal Caribbean", "Cordelia", "Costa", "MSC", "Star Cruises" };
        List<Cruise> cruises = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            cruises.add(cruiseRepository.save(Cruise.builder()
                    .city(cities.get(i % cities.size())).name(names[i])
                    .description(names[i] + " cruise line").build()));
        }
        Cruise first = cruises.get(0);
        String[] cabins = { "Interior", "Ocean View", "Balcony", "Suite", "Royal Suite" };
        for (int i = 0; i < N; i++) {
            cruiseRoomTypeRepository.save(CruiseRoomType.builder()
                    .cruise(first).name(cabins[i]).capacity(2 + (i % 3))
                    .price(BigDecimal.valueOf(15000L + i * 5000L)).build());
        }
        log.info("[DevDataSeeder] seeded 5 cruises + 5 cruise room types");
    }

    private void seedVehicles(List<City> cities) {
        if (vehicleRepository.count() > 0) return;
        String[][] data = {
                {"Sedan", "Car", "4"}, {"SUV", "Car", "6"}, {"Tempo Traveller", "Van", "12"},
                {"Mini Bus", "Bus", "20"}, {"Luxury Coach", "Bus", "40"}
        };
        for (int i = 0; i < N; i++) {
            vehicleRepository.save(VehicleEntity.builder()
                    .city(cities.get(i % cities.size())).name(data[i][0]).type(data[i][1])
                    .capacity(Integer.parseInt(data[i][2])).description(data[i][0] + " vehicle").build());
        }
        log.info("[DevDataSeeder] seeded 5 vehicles");
    }

    private void seedAddons(List<City> cities) {
        if (addonRepository.count() > 0) return;
        String[] names = { "Travel Insurance", "Airport Transfer", "Tour Guide", "SIM Card", "Photography" };
        for (int i = 0; i < N; i++) {
            addonRepository.save(Addon.builder()
                    .city(cities.get(i % cities.size())).name(names[i])
                    .description(names[i] + " add-on").price(BigDecimal.valueOf(500L + i * 300L))
                    .active(true).build());
        }
        log.info("[DevDataSeeder] seeded 5 addons");
    }

    private void seedSightseeings(List<City> cities) {
        if (sightseeingRepository.count() > 0) return;
        String[] titles = { "City Tour", "Beach Visit", "Museum", "Adventure Park", "Heritage Walk" };
        for (int i = 0; i < N; i++) {
            sightseeingRepository.save(Sightseeing.builder()
                    .city(cities.get(i % cities.size())).title(titles[i]).sequence(i + 1)
                    .estimatedHours(2.0 + i).suggestedStartTime("09:00")
                    .description(titles[i] + " experience").build());
        }
        log.info("[DevDataSeeder] seeded 5 sightseeings");
    }

    // ── customers / vendors / leads / bookings ────────────────────────────────

    private List<Customer> seedCustomers() {
        if (customerRepository.count() > 0) return customerRepository.findAll();
        String[] names = { "Arjun Sharma", "Priya Mehta", "Rahul Gupta", "Sunita Patel", "Vikram Singh" };
        List<Customer> out = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            out.add(customerRepository.save(Customer.builder()
                    .customerCode("CUS1000" + (i + 1)).name(names[i])
                    .phone("+91 98765 1000" + i).email("customer" + i + "@demo.crm")
                    .city("City " + (i + 1)).state("State " + (i + 1)).build()));
        }
        log.info("[DevDataSeeder] seeded {} customers", out.size());
        return out;
    }

    private void seedVendors() {
        if (vendorRepository.count() > 0) return;
        String[] names = { "Skyline Tours", "Coastal " +
                "DMC", "Peak Adventures", "Urban Stays", "Globe Transfers" };
        for (int i = 0; i < N; i++) {
            vendorRepository.save(Vendor.builder()
                    .vendorCode("VEN1000" + (i + 1)).vendorName(names[i])
                    .phone("+91 99000 2000" + i).email("vendor" + i + "@demo.crm")
                    .vendorType("DMC").city("City " + (i + 1)).build());
        }
        log.info("[DevDataSeeder] seeded 5 vendors");
    }

    private List<Lead> seedLeads(List<User> users) {
        if (leadRepository.count() > 0) return leadRepository.findAll();
        if (users.isEmpty()) {
            log.warn("[DevDataSeeder] no users to assign leads to — skipping leads");
            return List.of();
        }
        String[] names = { "Anushka Narkhede", "Sachin Kumar", "Meera Reddy", "Deepak Mishra", "Neha Kapoor" };
        LeadSource[] sources = { LeadSource.WEBSITE, LeadSource.REFERRAL, LeadSource.INSTAGRAM,
                LeadSource.GOOGLE_ADS, LeadSource.WHATSAPP };
        LeadType[] types = { LeadType.FRESH_LEAD, LeadType.REPEAT_CUSTOMER, LeadType.CORPORATE,
                LeadType.VIP, LeadType.FRESH_LEAD };
        LeadStage[] stages = { LeadStage.NEW_LEAD, LeadStage.CONTACTED, LeadStage.FOLLOW_UP,
                LeadStage.QUALIFIED, LeadStage.PROPOSAL_SENT };

        // Travel month, rooms, and itinerary legs per lead. Without a travelDate and an itinerary a
        // lead can only be scored on budget, so the package matcher would look broken on seed data.
        // The legs use the same destination/city names seedDestinations + seedCities create, which is
        // what lets TemplateMatchService resolve them back to real City ids.
        int[] travelMonths = { 10, 12, 3, 7, 11 };
        int[] rooms = { 1, 2, 1, 2, 3 };
        String[][][] itineraries = {
                { { "Goa", "Panaji", "3" } },
                { { "Manali", "Shimla", "4" } },
                { { "Dubai", "Dubai City", "5" } },
                { { "Phuket", "Phuket Town", "4" }, { "Sentosa", "Singapore City", "2" } },
                { { "Goa", "Panaji", "2" }, { "Manali", "Shimla", "3" } },
        };

        List<Lead> out = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            Lead lead = Lead.builder()
                    .customerName(names[i]).phone("+91 91234 5000" + i).email("lead" + i + "@demo.crm")
                    .leadSource(sources[i]).leadType(types[i]).leadStage(stages[i])
                    .assignedUser(users.get(i % users.size()))
                    .budget(BigDecimal.valueOf(50000L + i * 10000L))
                    .travelDate(nextOccurrenceOf(travelMonths[i]))
                    .rooms(rooms[i]).adults(2).children(i % 2)
                    .build();

            int dayNumber = 1;
            for (String[] leg : itineraries[i]) {
                lead.addItinerary(LeadItinerary.builder()
                        .destination(leg[0]).city(leg[1])
                        .nights(Integer.parseInt(leg[2]))
                        .dayNumber(dayNumber++)
                        .build());
            }
            out.add(leadRepository.save(lead));   // cascade = ALL carries the legs
        }
        log.info("[DevDataSeeder] seeded {} leads with itineraries", out.size());
        return out;
    }

    /** Mid-month, this year if it hasn't passed yet, else next year — so demo leads are never stale. */
    private static LocalDate nextOccurrenceOf(int month) {
        LocalDate today = LocalDate.now();
        LocalDate candidate = LocalDate.of(today.getYear(), month, 15);
        return candidate.isBefore(today) ? candidate.plusYears(1) : candidate;
    }

    private void seedBookings(List<Customer> customers, List<Destination> destinations, List<Lead> leads) {
        if (bookingRepository.count() > 0 || customers.isEmpty()) return;
        BookingStatus[] statuses = { BookingStatus.CONFIRMED, BookingStatus.PENDING, BookingStatus.CONFIRMED,
                BookingStatus.COMPLETED, BookingStatus.PENDING };
        PaymentStatus[] payments = { PaymentStatus.PAID, PaymentStatus.PARTIAL, PaymentStatus.UNPAID,
                PaymentStatus.PAID, PaymentStatus.PARTIAL };
        for (int i = 0; i < N; i++) {
            Customer cust = customers.get(i % customers.size());
            Destination dest = destinations.isEmpty() ? null : destinations.get(i % destinations.size());
            BigDecimal amount = BigDecimal.valueOf(60000L + i * 15000L);
            BigDecimal paid = payments[i] == PaymentStatus.PAID ? amount
                    : payments[i] == PaymentStatus.PARTIAL ? amount.divide(BigDecimal.valueOf(2)) : BigDecimal.ZERO;
            bookingRepository.save(Booking.builder()
                    .bookingCode("BK1000" + (i + 1))
                    .customerId(cust.getId())
                    .customerNameSnapshot(cust.getName())
                    .destinationId(dest != null ? dest.getId() : null)
                    .destinationSnapshot(dest != null ? dest.getName() : "Custom Package")
                    .leadId(leads.isEmpty() ? null : leads.get(i % leads.size()).getId())
                    .customerAmount(amount)
                    .vendorCost(amount.multiply(BigDecimal.valueOf(0.7)))
                    .gst(BigDecimal.ZERO).tcs(BigDecimal.ZERO)
                    .totalPayable(amount)
                    .paidAmount(paid)
                    .netProfit(amount.multiply(BigDecimal.valueOf(0.3)))
                    .status(statuses[i])
                    .paymentStatus(payments[i])
                    .bookingDate(LocalDate.now().minusDays(i))
                    .travelDate(LocalDate.now().plusDays(15L + i))
                    .build());
        }
        log.info("[DevDataSeeder] seeded 5 bookings");
    }

    // ── reminders / booking reminders ─────────────────────────────────────────

    private void seedReminders() {
        if (reminderRepository.count() > 0) return;
        ReminderType[] types = { ReminderType.First_contact, ReminderType.Follow_up, ReminderType.Quotation,
                ReminderType.Payment, ReminderType.Document };
        String[] names = { "Pratik", "Priyanshu", "Anushka", "Sachin", "Meera" };
        for (int i = 0; i < N; i++) {
            reminderRepository.save(Reminder.builder()
                    .title("Contact lead: " + names[i])
                    .description("Auto-seeded reminder")
                    .type(types[i])
                    .leadName(names[i]).phone("+91 88888 0000" + i)
                    .dueDate(Instant.now().plus(i + 1, ChronoUnit.DAYS))
                    .build());
        }
        log.info("[DevDataSeeder] seeded 5 reminders");
    }

    private void seedBookingReminders() {
        if (bookingReminderRepository.count() > 0) return;
        BookingReminderType[] types = { BookingReminderType.Payment_due, BookingReminderType.Final_payment,
                BookingReminderType.Document, BookingReminderType.Visa, BookingReminderType.Travel_date };
        String[] custs = { "Arjun Sharma", "Priya Mehta", "Rahul Gupta", "Sunita Patel", "Vikram Singh" };
        for (int i = 0; i < N; i++) {
            bookingReminderRepository.save(BookingReminder.builder()
                    .bookingCode("BK1000" + (i + 1)).customerName(custs[i])
                    .phone("+91 98765 1000" + i).destination("Destination " + (i + 1))
                    .reminderType(types[i]).message("Auto-seeded booking reminder")
                    .travelDate(Instant.now().plus(20L + i, ChronoUnit.DAYS))
                    .reminderDate(Instant.now().plus(i + 1, ChronoUnit.DAYS))
                    .amount(50000.0 + i * 10000).build());
        }
        log.info("[DevDataSeeder] seeded 5 booking reminders");
    }

    // ── company / permissions / tax / notification settings ───────────────────

    private void seedPermissionTemplates() {
        if (permissionTemplateRepository.count() > 0) return;
        String[] labels = { "Basic Access", "Sales Team", "Support", "Manager", "Full Access" };
        for (int i = 0; i < N; i++) {
            permissionTemplateRepository.save(PermissionTemplate.builder()
                    .value("template_" + (i + 1)).label(labels[i])
                    .description(labels[i] + " template").isDefault(i == 0)
                    .permissionsJson("{}").build());
        }
        log.info("[DevDataSeeder] seeded 5 permission templates");
    }

    private void seedTaxRates() {
        if (taxRateRepository.count() > 0) return;
        String[] typesArr = { "GST", "TCS", "TDS", "VAT", "Service Tax" };
        double[] rates = { 5.0, 5.0, 2.0, 7.5, 18.0 };
        for (int i = 0; i < N; i++) {
            taxRateRepository.save(TaxRate.builder()
                    .type(typesArr[i]).rate(BigDecimal.valueOf(rates[i]))
                    .calculation("Additive").effectiveFrom(LocalDate.now())
                    .description(typesArr[i] + " rate").isActive(true).build());
        }
        log.info("[DevDataSeeder] seeded 5 tax rates");
    }

    private void seedCompany() {
        if (companyRepository.count() > 0) return;
        companyRepository.save(Company.builder()
                .name("Demo Travels").prefix("DEMO").email("org@demo.crm")
                .phone("+91 90000 00000").website("https://demo.crm")
                .status("Active").totalReviews(0).tripsSold(0)
                .state("Demo State").build());
        log.info("[DevDataSeeder] seeded company profile");
    }

    private void seedNotificationSettings() {
        if (notificationSettingRepository.count() > 0) return;
        notificationSettingRepository.save(NotificationSetting.builder()
                .settingsJson("[]").build());
        log.info("[DevDataSeeder] seeded notification settings");
    }

    // ── fleet / vehicle diary ─────────────────────────────────────────────────

    /** Fleet seeds 7 rows per entity (independent of the platform-wide {@link #N}). */
    private static final int FLEET_N = 7;

    /**
     * Seeds the Vehicle Diary: 7 vehicles, 7 drivers, 7 trips, 7 fuel logs and 7 maintenance
     * logs. Statuses, odometers, document/service dates and log dates are kept mutually
     * consistent (two ONGOING trips hold their vehicles ON_TRIP; several docs expire within
     * the dashboard window or are already overdue; a couple of vehicles fall due for service;
     * fuel/maintenance logs land in the current month) so the dashboard counts, KPIs, expiry
     * alerts and service-due tiles all show live data. Each table is guarded independently for
     * idempotency (seeded only when empty).
     */
    private void seedFleet() {
        List<FleetVehicle> vehicles = fleetVehicleRepository.count() > 0
                ? fleetVehicleRepository.findAll()
                : seedFleetVehicles();
        List<FleetDriver> drivers = fleetDriverRepository.count() > 0
                ? fleetDriverRepository.findAll()
                : seedFleetDrivers();
        if (vehicles.isEmpty() || drivers.isEmpty()) return;

        if (fleetTripRepository.count() == 0) seedFleetTrips(vehicles, drivers);
        if (fleetFuelLogRepository.count() == 0) seedFleetFuelLogs(vehicles);
        if (fleetMaintenanceLogRepository.count() == 0) seedFleetMaintenanceLogs(vehicles);
    }

    private List<FleetVehicle> seedFleetVehicles() {
        LocalDate today = LocalDate.now();
        // Vendor snapshot for VENDOR/RENTED-owned vehicles (logical FK, same pattern as booking snapshots).
        Vendor vendor = vendorRepository.findAll().stream().findFirst().orElse(null);

        String[][] spec = {   // number, type, make, model, year, seats
                {"MH12 AB 1234", "Sedan",           "Toyota",   "Etios",          "2021", "4"},
                {"MH14 CD 5678", "SUV",             "Toyota",   "Innova Crysta",  "2022", "7"},
                {"MH01 EF 9012", "Tempo Traveller", "Force",    "Traveller 3350", "2020", "12"},
                {"MH02 GH 3456", "Mini Bus",        "Tata",     "Winger",         "2019", "20"},
                {"MH03 IJ 7890", "Luxury Coach",    "Volvo",    "9600",           "2023", "40"},
                {"MH04 KL 2345", "Sedan",           "Honda",    "City",           "2022", "4"},
                {"MH05 MN 6789", "SUV",             "Mahindra", "Scorpio N",      "2021", "7"},
        };
        FleetOwnerType[] owners = { FleetOwnerType.OWN, FleetOwnerType.OWN, FleetOwnerType.VENDOR,
                FleetOwnerType.RENTED, FleetOwnerType.OWN, FleetOwnerType.VENDOR, FleetOwnerType.OWN };
        // v4 & v7 stay AVAILABLE here — seedFleetTrips flips them to ON_TRIP alongside their ongoing trips.
        FleetVehicleStatus[] statuses = {
                FleetVehicleStatus.AVAILABLE, FleetVehicleStatus.AVAILABLE, FleetVehicleStatus.MAINTENANCE,
                FleetVehicleStatus.AVAILABLE, FleetVehicleStatus.OUT_OF_SERVICE, FleetVehicleStatus.AVAILABLE,
                FleetVehicleStatus.AVAILABLE };
        int[] odo = { 45210, 78300, 120500, 63000, 15400, 32000, 88000 };
        int[][] docDays = {   // days-from-today: insurance, rc, permit, puc (negative = expired)
                { 200, 400,  25,   5 },
                {  10, 300, 180,  -3 },
                {  60,  90,  45, 120 },
                {  15, 160,  75,  40 },
                { 365, 365, 300, 250 },
                {  28,  20, 400, 500 },
                { -10, 500, 200,  90 },
        };
        int[] svcDays = { 10, 60, 200, 90, 365, 8, 45 };
        // v2 (78500-78300=200) and v7 (88300-88000=300) fall due by odometer (≤500 threshold).
        int[] svcKm = { 46000, 78500, 125000, 70000, 30000, 40000, 88300 };

        List<FleetVehicle> out = new ArrayList<>();
        for (int i = 0; i < FLEET_N; i++) {
            String[] s = spec[i];
            boolean linked = owners[i] != FleetOwnerType.OWN && vendor != null;
            out.add(fleetVehicleRepository.save(FleetVehicle.builder()
                    .vehicleNumber(s[0]).type(s[1]).make(s[2]).model(s[3])
                    .year(Integer.parseInt(s[4])).seatingCapacity(Integer.parseInt(s[5]))
                    .ownerType(owners[i])
                    .vendorId(linked ? vendor.getId() : null)
                    .vendorPublicId(linked ? vendor.getPublicId() : null)
                    .vendorName(linked ? vendor.getVendorName() : null)
                    .status(statuses[i])
                    .lastOdometer(odo[i])
                    .insuranceExpiry(today.plusDays(docDays[i][0]))
                    .rcExpiry(today.plusDays(docDays[i][1]))
                    .permitExpiry(today.plusDays(docDays[i][2]))
                    .pucExpiry(today.plusDays(docDays[i][3]))
                    .nextServiceDueDate(today.plusDays(svcDays[i]))
                    .nextServiceDueKm(svcKm[i])
                    .notes("Auto-seeded fleet vehicle")
                    .build()));
        }
        log.info("[DevDataSeeder] seeded {} fleet vehicles", out.size());
        return out;
    }

    private List<FleetDriver> seedFleetDrivers() {
        LocalDate today = LocalDate.now();
        String[] names = { "Ramesh Yadav", "Suresh Pawar", "Imran Shaikh", "Datta Patil",
                "Vijay More", "Kunal Jadhav", "Sagar Bhosale" };
        String[] phones = { "+91 90000 11111", "+91 90000 22222", "+91 90000 33333",
                "+91 90000 44444", "+91 90000 55555", "+91 90000 66666", "+91 90000 77777" };
        String[] licenses = { "MH0120180011111", "MH0120190022222", "MH0120200033333",
                "MH0120170044444", "MH0120210055555", "MH0120160066666", "MH0120220077777" };
        int[] licDays = { 400, 20, 5, 250, -10, 15, 600 };   // drivers 2, 3 & 6 expire within the alert window
        FleetDriverStatus[] statuses = {
                FleetDriverStatus.ACTIVE, FleetDriverStatus.ACTIVE, FleetDriverStatus.ACTIVE,
                FleetDriverStatus.ACTIVE, FleetDriverStatus.INACTIVE, FleetDriverStatus.ACTIVE,
                FleetDriverStatus.ACTIVE };
        List<FleetDriver> out = new ArrayList<>();
        for (int i = 0; i < FLEET_N; i++) {
            out.add(fleetDriverRepository.save(FleetDriver.builder()
                    .name(names[i]).phone(phones[i]).licenseNumber(licenses[i])
                    .licenseExpiry(today.plusDays(licDays[i])).status(statuses[i])
                    .notes("Auto-seeded driver").build()));
        }
        log.info("[DevDataSeeder] seeded {} fleet drivers", out.size());
        return out;
    }

    private void seedFleetTrips(List<FleetVehicle> vehicles, List<FleetDriver> drivers) {
        LocalDateTime now = LocalDateTime.now();
        List<Booking> bookings = bookingRepository.findAll();
        Booking bk1 = bookings.size() > 0 ? bookings.get(0) : null;
        Booking bk2 = bookings.size() > 1 ? bookings.get(1) : null;

        FleetVehicle v1 = vehicles.get(0);
        FleetVehicle v2 = vehicles.get(1);
        FleetVehicle v3 = vehicles.get(2);
        FleetVehicle v4 = vehicles.get(3);
        FleetVehicle v6 = vehicles.get(5 % vehicles.size());
        FleetVehicle v7 = vehicles.get(6 % vehicles.size());
        FleetDriver d1 = drivers.get(0);
        FleetDriver d2 = drivers.get(1);
        FleetDriver d3 = drivers.get(2);
        FleetDriver d4 = drivers.get(3);
        FleetDriver d5 = drivers.get(4);
        FleetDriver d6 = drivers.get(5 % drivers.size());
        FleetDriver d7 = drivers.get(6 % drivers.size());

        // 1) COMPLETED this month — Mumbai → Lonavala (linked to a booking)
        fleetTripRepository.save(FleetTrip.builder()
                .vehicle(v1).driver(d1)
                .bookingId(bk1 != null ? bk1.getId() : null)
                .bookingPublicId(bk1 != null ? bk1.getPublicId() : null)
                .bookingCode(bk1 != null ? bk1.getBookingCode() : null)
                .status(FleetTripStatus.COMPLETED)
                .startDatetime(now.minusHours(9).withSecond(0).withNano(0))
                .endDatetime(now.minusHours(3).withSecond(0).withNano(0))
                .startOdometer(45000).endOdometer(45210).distanceKm(210)
                .routeFrom("Mumbai").routeTo("Lonavala").purpose("Airport transfer")
                .fuelCost(BigDecimal.valueOf(1800)).tollCost(BigDecimal.valueOf(300))
                .driverAllowance(BigDecimal.valueOf(500)).remarks("Completed on time").build());

        // 2) COMPLETED this month — Pune → Mahabaleshwar (linked to a booking)
        fleetTripRepository.save(FleetTrip.builder()
                .vehicle(v2).driver(d2)
                .bookingId(bk2 != null ? bk2.getId() : null)
                .bookingPublicId(bk2 != null ? bk2.getPublicId() : null)
                .bookingCode(bk2 != null ? bk2.getBookingCode() : null)
                .status(FleetTripStatus.COMPLETED)
                .startDatetime(now.minusDays(1).withHour(8).withMinute(0).withSecond(0).withNano(0))
                .endDatetime(now.minusDays(1).withHour(16).withMinute(0).withSecond(0).withNano(0))
                .startOdometer(78000).endOdometer(78300).distanceKm(300)
                .routeFrom("Pune").routeTo("Mahabaleshwar").purpose("Sightseeing tour")
                .fuelCost(BigDecimal.valueOf(2500)).tollCost(BigDecimal.valueOf(450))
                .driverAllowance(BigDecimal.valueOf(700)).build());

        // 3) COMPLETED this month — Mumbai → Alibaug
        fleetTripRepository.save(FleetTrip.builder()
                .vehicle(v6).driver(d6)
                .status(FleetTripStatus.COMPLETED)
                .startDatetime(now.minusDays(2).withHour(7).withMinute(30).withSecond(0).withNano(0))
                .endDatetime(now.minusDays(2).withHour(13).withMinute(0).withSecond(0).withNano(0))
                .startOdometer(31850).endOdometer(32000).distanceKm(150)
                .routeFrom("Mumbai").routeTo("Alibaug").purpose("Day trip")
                .fuelCost(BigDecimal.valueOf(1500)).tollCost(BigDecimal.valueOf(250))
                .driverAllowance(BigDecimal.valueOf(450)).build());

        // 4) ONGOING — Mumbai → Nashik (holds v4 ON_TRIP)
        v4.setStatus(FleetVehicleStatus.ON_TRIP);
        v4.bumpOdometer(63000);
        fleetVehicleRepository.save(v4);
        fleetTripRepository.save(FleetTrip.builder()
                .vehicle(v4).driver(d4)
                .status(FleetTripStatus.ONGOING)
                .startDatetime(now.minusHours(2).withSecond(0).withNano(0))
                .startOdometer(63000)
                .routeFrom("Mumbai").routeTo("Nashik").purpose("Group tour").build());

        // 5) ONGOING — Pune → Kolhapur (holds v7 ON_TRIP)
        v7.setStatus(FleetVehicleStatus.ON_TRIP);
        v7.bumpOdometer(88000);
        fleetVehicleRepository.save(v7);
        fleetTripRepository.save(FleetTrip.builder()
                .vehicle(v7).driver(d7)
                .status(FleetTripStatus.ONGOING)
                .startDatetime(now.minusHours(5).withSecond(0).withNano(0))
                .startOdometer(88000)
                .routeFrom("Pune").routeTo("Kolhapur").purpose("Corporate tour").build());

        // 6) PLANNED (upcoming, tomorrow) — Mumbai → Pune
        fleetTripRepository.save(FleetTrip.builder()
                .vehicle(v1).driver(d3)
                .status(FleetTripStatus.PLANNED)
                .startDatetime(now.plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0))
                .routeFrom("Mumbai").routeTo("Pune").purpose("Corporate pickup").build());

        // 7) PLANNED (upcoming, in 3 days) — Mumbai → Shirdi
        fleetTripRepository.save(FleetTrip.builder()
                .vehicle(v6).driver(d5)
                .status(FleetTripStatus.PLANNED)
                .startDatetime(now.plusDays(3).withHour(6).withMinute(0).withSecond(0).withNano(0))
                .routeFrom("Mumbai").routeTo("Shirdi").purpose("Pilgrimage").build());

        log.info("[DevDataSeeder] seeded {} fleet trips", FLEET_N);
    }

    private void seedFleetFuelLogs(List<FleetVehicle> vehicles) {
        LocalDate today = LocalDate.now();
        // vehicleIndex, days-ago (kept small so most land in the current month), liters, cost, odometer
        int[][] spec = {
                { 0, 0, 35, 3800, 45210 },
                { 1, 1, 45, 4900, 78300 },
                { 2, 2, 55, 6000, 120500 },
                { 3, 3, 60, 6500, 63000 },
                { 4, 5, 80, 8800, 15400 },
                { 5, 1, 30, 3300, 32000 },
                { 6, 4, 40, 4400, 88000 },
        };
        for (int[] f : spec) {
            FleetVehicle v = vehicles.get(f[0] % vehicles.size());
            fleetFuelLogRepository.save(FleetFuelLog.builder()
                    .vehicle(v).date(today.minusDays(f[1]))
                    .liters(BigDecimal.valueOf(f[2])).cost(BigDecimal.valueOf(f[3]))
                    .odometer(f[4]).notes("Auto-seeded fuel log").build());
        }
        log.info("[DevDataSeeder] seeded {} fleet fuel logs", spec.length);
    }

    private void seedFleetMaintenanceLogs(List<FleetVehicle> vehicles) {
        LocalDate today = LocalDate.now();
        Object[][] spec = {   // vehicleIndex, days-ago, serviceType, cost, vendorName, odometer
                { 0,  0, "General Service",     4500, "Speedy Motors", 45000 },
                { 1,  2, "Oil Change",          2200, "Auto Care",     78000 },
                { 2, 10, "Brake Repair",        6800, "Force Service", 120000 },
                { 3, 20, "Tyre Replacement",   12000, "Tyre World",    62000 },
                { 4,  1, "AC Service",          3500, "Cool Air",      15000 },
                { 5,  5, "General Service",     5200, "Speedy Motors", 31000 },
                { 6,  3, "Battery Replacement", 7800, "Power Cells",   87000 },
        };
        for (Object[] m : spec) {
            FleetVehicle v = vehicles.get((int) m[0] % vehicles.size());
            LocalDate serviceDate = today.minusDays((int) m[1]);
            fleetMaintenanceLogRepository.save(FleetMaintenanceLog.builder()
                    .vehicle(v).serviceDate(serviceDate)
                    .serviceType((String) m[2]).cost(BigDecimal.valueOf((int) m[3]))
                    .vendorName((String) m[4]).odometer((int) m[5])
                    .nextServiceDueDate(serviceDate.plusMonths(6))
                    .nextServiceDueKm((int) m[5] + 10000)
                    .notes("Auto-seeded maintenance log").build());
        }
        log.info("[DevDataSeeder] seeded {} fleet maintenance logs", spec.length);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Quotation templates
    // ══════════════════════════════════════════════════════════════════════════

    /** City name → the destination {@code seedDestinations} paired it with. */
    private static final Map<String, String> DEMO_CITY_DESTINATIONS = Map.of(
            "Panaji", "Goa",
            "Shimla", "Manali",
            "Dubai City", "Dubai",
            "Phuket Town", "Phuket",
            "Singapore City", "Sentosa");

    /**
     * Six templates chosen so the five seeded leads produce a visibly different score on each of the
     * matcher's dimensions rather than a wall of 100 %:
     *
     * <ul>
     *   <li><b>Goa &amp; Manali Grand Tour</b> is a near-perfect fit for Neha (same two cities, same
     *       5 nights, ₹88 k against her ₹90 k, November) — a ~100 % badge.</li>
     *   <li><b>Dubai City Break</b> and <b>Phuket &amp; Singapore Combo</b> match their leads on
     *       destination, duration and season but cost roughly twice the budget, so they land around
     *       76 % — the clearest demonstration that budget is scored, and that over-budget hurts.</li>
     *   <li><b>Goa Beach Escape</b> also surfaces for Neha at ~65 %: it covers one of her two cities
     *       and is two nights short. Good for eyeballing partial coverage.</li>
     *   <li><b>Kerala Backwaters</b> is archived, and must never appear in a match result.</li>
     * </ul>
     */
    private void seedQuotationTemplates(List<City> cities) {
        if (quotationTemplateRepository.count() > 0) return;

        Map<String, City> byName = new HashMap<>();
        for (City c : cities) byName.put(c.getName(), c);

        City panaji = byName.get("Panaji");
        City shimla = byName.get("Shimla");
        City dubaiCity = byName.get("Dubai City");
        City phuketTown = byName.get("Phuket Town");
        City singaporeCity = byName.get("Singapore City");
        if (panaji == null || shimla == null || dubaiCity == null
                || phuketTown == null || singaporeCity == null) {
            log.warn("[DevDataSeeder] expected demo cities missing — skipping quotation templates");
            return;
        }

        // 1 ─ Goa Beach Escape
        QuotationTemplate goa = newTemplate("Goa Beach Escape",
                "Three unhurried nights on the North Goa coast: beaches, the Latin quarter, "
                        + "and a day out at Dudhsagar falls.",
                3, 4, 55_000, Set.of(10, 11, 12, 1, 2), true);
        addDay(goa, 1, panaji, 3, "Arrival & Baga Beach sunset", 1_200);
        addDay(goa, 2, panaji, 0, "Old Goa churches and the Panjim Latin quarter", 1_800);
        addDay(goa, 3, panaji, 0, "Dudhsagar Falls jeep safari", 2_500);
        addHotel(goa, 0, "Taj Holiday Village", "Panaji", 4, "Deluxe Garden View", "Breakfast", 9_500, 3);
        goa.getInclusions().addAll(List.of("Airport transfers", "Daily breakfast", "Dudhsagar jeep safari"));
        goa.getExclusions().addAll(List.of("Airfare", "Water sports", "Personal expenses"));
        quotationTemplateRepository.save(goa);

        // 2 ─ Manali Snow Retreat
        QuotationTemplate manali = newTemplate("Manali Snow Retreat",
                "Four nights in the Himalayas — Solang valley, Rohtang pass and the old town.",
                4, 3, 62_000, Set.of(12, 1, 2, 3), true);
        addDay(manali, 1, shimla, 4, "Arrival and Mall Road evening", 900);
        addDay(manali, 2, shimla, 0, "Solang Valley adventure day", 2_200);
        addDay(manali, 3, shimla, 0, "Rohtang Pass excursion", 3_000);
        addDay(manali, 4, shimla, 0, "Hadimba temple and old Manali cafés", 1_100);
        addHotel(manali, 0, "Snow Valley Resorts", "Shimla", 3, "Valley View", "Half Board", 6_800, 4);
        manali.getInclusions().addAll(List.of("Volvo coach transfers", "Daily breakfast & dinner", "Rohtang permit"));
        manali.getExclusions().addAll(List.of("Snow gear rental", "Ropeway tickets", "Personal expenses"));
        quotationTemplateRepository.save(manali);

        // 3 ─ Dubai City Break (year-round: no season months)
        QuotationTemplate dubai = newTemplate("Dubai City Break",
                "Five nights of skyline, desert and souk. Sold year-round.",
                5, 5, 145_000, Set.of(), true);
        addDay(dubai, 1, dubaiCity, 5, "Arrival and Dubai Marina cruise", 4_500);
        addDay(dubai, 2, dubaiCity, 0, "Burj Khalifa and Dubai Mall fountains", 5_200);
        addDay(dubai, 3, dubaiCity, 0, "Desert safari with BBQ dinner", 4_800);
        addDay(dubai, 4, dubaiCity, 0, "Old Dubai souks and abra ride", 2_400);
        addDay(dubai, 5, dubaiCity, 0, "Palm Jumeirah and departure", 2_000);
        addHotel(dubai, 0, "Address Downtown", "Dubai City", 5, "Skyline King", "Breakfast", 24_000, 5);
        dubai.getInclusions().addAll(List.of("Visa assistance", "Daily breakfast", "Desert safari", "All transfers"));
        dubai.getExclusions().addAll(List.of("Airfare", "Travel insurance", "Optional tours"));
        quotationTemplateRepository.save(dubai);

        // 4 ─ Phuket & Singapore Combo (two cities)
        QuotationTemplate combo = newTemplate("Phuket & Singapore Combo",
                "Six nights across two countries — Andaman beaches, then the island city.",
                6, 4, 165_000, Set.of(6, 7, 8, 9), true);
        addDay(combo, 1, phuketTown, 4, "Arrival, Patong beach evening", 2_100);
        addDay(combo, 2, phuketTown, 0, "Phi Phi islands speedboat tour", 4_600);
        addDay(combo, 3, phuketTown, 0, "Phang Nga bay and James Bond island", 4_200);
        addDay(combo, 4, phuketTown, 0, "Old Phuket town and free evening", 1_500);
        addDay(combo, 5, singaporeCity, 2, "Fly to Singapore, Gardens by the Bay", 3_800);
        addDay(combo, 6, singaporeCity, 0, "Sentosa and Universal Studios", 6_500);
        addHotel(combo, 0, "Novotel Phuket Resort", "Phuket Town", 4, "Superior Pool View", "Breakfast", 8_200, 4);
        addHotel(combo, 1, "Village Hotel Sentosa", "Singapore City", 4, "Deluxe", "Breakfast", 13_500, 2);
        combo.getInclusions().addAll(List.of("Inter-country flight", "Daily breakfast", "Island tours", "All transfers"));
        combo.getExclusions().addAll(List.of("International airfare", "Visa fees", "Universal Studios tickets"));
        quotationTemplateRepository.save(combo);

        // 5 ─ Goa & Manali Grand Tour (two cities, the near-perfect fit for lead #5)
        QuotationTemplate grand = newTemplate("Goa & Manali Grand Tour",
                "Beach then mountain: two nights in Goa, three in the Himalayas.",
                5, 3, 88_000, Set.of(10, 11, 12), true);
        addDay(grand, 1, panaji, 2, "Arrival in Goa, Candolim beach", 1_000);
        addDay(grand, 2, panaji, 0, "Panjim heritage walk and river cruise", 1_600);
        addDay(grand, 3, shimla, 3, "Fly north, arrive Manali", 1_200);
        addDay(grand, 4, shimla, 0, "Solang Valley and Hadimba temple", 2_200);
        addDay(grand, 5, shimla, 0, "Old Manali and departure", 900);
        addHotel(grand, 0, "Fairfield by Marriott Goa", "Panaji", 3, "Superior", "Breakfast", 6_400, 2);
        addHotel(grand, 1, "Manuallaya Resort", "Shimla", 3, "Mountain View", "Half Board", 7_100, 3);
        grand.getInclusions().addAll(List.of("Domestic flight Goa→Delhi", "Daily breakfast", "All transfers"));
        grand.getExclusions().addAll(List.of("Airfare to Goa", "Adventure activities", "Personal expenses"));
        quotationTemplateRepository.save(grand);

        // 6 ─ Archived: must never surface in a match result.
        QuotationTemplate retired = newTemplate("Kerala Backwaters (retired)",
                "Superseded by the 2026 Kerala catalogue. Kept for reference only.",
                4, 4, 74_000, Set.of(9, 10, 11), false);
        // No cityId here on purpose: it also exercises the name-only city path in the scorer.
        retired.addItineraryDay(QuotationTemplateItinerary.builder()
                .dayNumber(1).cityName("Alleppey").destinationName("Kerala")
                .nights(4).title("Houseboat on the backwaters")
                .description("Overnight houseboat through the Alleppey canals.")
                .pricePerPax(BigDecimal.valueOf(3_400)).build());
        quotationTemplateRepository.save(retired);

        log.info("[DevDataSeeder] seeded 6 quotation templates (1 archived)");
    }

    private QuotationTemplate newTemplate(String name, String description, int nights, int hotelTier,
                                          long basePrice, Set<Integer> seasonMonths, boolean active) {
        QuotationTemplate template = QuotationTemplate.builder()
                .name(name)
                .description(description)
                .active(active)
                .durationNights(nights)
                .hotelTier(hotelTier)
                .basePrice(BigDecimal.valueOf(basePrice))
                .build();
        template.getSeasonMonths().addAll(seasonMonths);
        return template;
    }

    private void addDay(QuotationTemplate template, int dayNumber, City city, int nights,
                        String title, long pricePerPax) {
        template.addItineraryDay(QuotationTemplateItinerary.builder()
                .dayNumber(dayNumber)
                .cityId(city.getId())
                .cityName(city.getName())
                // Read from the constant, not city.getDestination(): when the cities already existed
                // this list came back from findAll() detached, and the association is a lazy proxy.
                .destinationName(DEMO_CITY_DESTINATIONS.get(city.getName()))
                .nights(nights)
                .title(title)
                .description(title + ".")
                .pricePerPax(BigDecimal.valueOf(pricePerPax))
                .build());
    }

    private void addHotel(QuotationTemplate template, int sortOrder, String hotel, String city,
                          int stars, String roomType, String mealPlan, long pricePerRoom, int nights) {
        template.addHotel(QuotationTemplateHotel.builder()
                .sortOrder(sortOrder)
                .name(hotel).city(city).stars(stars)
                .roomType(roomType).mealPlan(mealPlan).refundable(true)
                .pricePerRoom(BigDecimal.valueOf(pricePerRoom))
                .rooms(null)          // null ⇒ "however many rooms the lead needs", filled at apply time
                .nights(nights)
                .build());
    }
}
