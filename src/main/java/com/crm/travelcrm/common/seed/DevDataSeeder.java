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
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.enums.LeadSource;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadType;
import com.crm.travelcrm.lead.repository.LeadRepository;
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
import com.crm.travelcrm.tenent.entity.Tenant;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

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

    @Override
    public void run(String... args) {
        log.info("[DevDataSeeder] starting (app.seed.enabled=true)…");

        seedSuperAdmin();
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

            List<Customer> customers = seedCustomers();
            seedVendors();
            List<Lead> leads = seedLeads(users);
            seedBookings(customers, destinations, leads);

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
        String[] names = { "Skyline Tours", "Coastal DMC", "Peak Adventures", "Urban Stays", "Globe Transfers" };
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
        List<Lead> out = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            out.add(leadRepository.save(Lead.builder()
                    .customerName(names[i]).phone("+91 91234 5000" + i).email("lead" + i + "@demo.crm")
                    .leadSource(sources[i]).leadType(types[i]).leadStage(stages[i])
                    .assignedUser(users.get(i % users.size()))
                    .estimatedValue(BigDecimal.valueOf(50000L + i * 10000L))
                    .adults(2).children(i % 2).build()));
        }
        log.info("[DevDataSeeder] seeded {} leads", out.size());
        return out;
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
}
