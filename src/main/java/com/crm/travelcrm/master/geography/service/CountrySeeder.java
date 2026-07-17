package com.crm.travelcrm.master.geography.service;

import com.crm.travelcrm.master.geography.entity.Country;
import com.crm.travelcrm.master.geography.repository.CountryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Gives every tenant the full ISO 3166-1 country list.
 *
 * <p><b>Why this exists.</b> {@code Country} is a {@link com.crm.travelcrm.common.entity.BaseTenantEntity},
 * so each tenant owns its own rows — and nothing was creating them in production.
 * {@code DevDataSeeder} seeds five countries but is hard-disabled by {@code app.seed.enabled=false},
 * so a freshly provisioned tenant had **zero** countries. That is not a cosmetic gap: every
 * {@code Destination} carries a NOT NULL {@code country_id} FK, so with no countries the whole
 * Destination → City → Hotel cascade is unusable from day one.
 *
 * <p><b>Idempotent per ROW, not per table.</b> The obvious shape — "if the table is empty, bulk
 * insert" — is a trap: the day a country is added to the JSON, an empty-check never runs again and no
 * existing tenant ever receives it. Keying on {@code code} instead means re-running always converges,
 * and adding a country later is just an edit to the resource.
 *
 * <p><b>Existing rows are never touched.</b> A tenant can rename or delete countries through the
 * existing Country CRUD, and re-seeding must not resurrect or overwrite their edits — it only fills in
 * what is absent. A country the tenant deliberately trashed stays trashed: the check reads through the
 * soft-delete filter, so the row still occupies its {@code (tenant_id, code)} unique slot and the
 * insert is skipped rather than colliding.
 *
 * <p>Mirrors {@code CancellationPolicySeeder}: {@code @Transactional} so it joins the caller's
 * transaction during tenant creation, or opens its own during the backfill.
 */
@Service
@RequiredArgsConstructor
public class CountrySeeder {

    private static final Logger log = LogManager.getLogger(CountrySeeder.class);

    /**
     * Not under {@code db/} — deliberately. {@code spring.sql.init.schema-locations} points there and
     * Spring executes that folder's SQL on every boot from inside the jar; a JSON file living beside it
     * is an invitation to a very confusing failure.
     */
    private static final String RESOURCE = "data/countries.json";

    private final CountryRepository countryRepository;
    private final ObjectMapper objectMapper;

    /** One row of {@code data/countries.json}. */
    public record CountrySeed(String name, String isoCode2, String isoCode3, String dialCode) {}

    /**
     * Ensure this tenant has every catalogue country. Only inserts what is missing.
     *
     * @return how many rows were created (0 when the tenant was already complete)
     */
    @Transactional
    public int ensureCountries(Long tenantId) {
        List<CountrySeed> catalogue = readCatalogue();
        if (catalogue.isEmpty()) {
            return 0;
        }

        // One query for the whole tenant rather than 198 existsBy calls — this runs inside tenant
        // creation, on the request thread.
        Set<String> present = new HashSet<>();
        for (Country existing : countryRepository.findAllByTenantIdOrderByNameAsc(tenantId)) {
            if (existing.getCode() != null) {
                present.add(existing.getCode().toUpperCase(Locale.ROOT));
            }
        }

        List<Country> toInsert = new ArrayList<>();
        for (CountrySeed seed : catalogue) {
            String code = seed.isoCode2().toUpperCase(Locale.ROOT);
            if (present.contains(code)) {
                continue;   // the tenant already has it — never overwrite their edits
            }
            Country country = Country.builder()
                    .name(seed.name())
                    .code(code)
                    .isoCode3(seed.isoCode3())
                    .dialCode(seed.dialCode())
                    .build();
            // EXPLICIT, never ambient: this runs on tenant creation (where TenantContext is the
            // CREATING user's tenant, not the new one) and on the boot backfill (where there is no
            // context at all). TenantEntityListener accepts an explicit tenantId in both cases.
            country.setTenantId(tenantId);
            toInsert.add(country);
        }

        if (toInsert.isEmpty()) {
            return 0;
        }
        countryRepository.saveAll(toInsert);
        log.info("Seeded {} country/countries for tenant {}", toInsert.size(), tenantId);
        return toInsert.size();
    }

    /**
     * The bundled catalogue.
     *
     * <p>Read fresh per call rather than cached in a field: this runs once per tenant at creation and
     * once per tenant at boot, so parsing a ~200-entry file is irrelevant, while a static cache would
     * be one more thing to invalidate. A missing or malformed resource throws — it is packaged in the
     * jar, so failing loudly beats silently giving every future tenant an empty dropdown.
     */
    private List<CountrySeed> readCatalogue() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            CountrySeed[] rows = objectMapper.readValue(in, CountrySeed[].class);
            return List.of(rows);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not read the bundled country catalogue at classpath:" + RESOURCE
                            + ". Without it every new tenant gets an empty Country dropdown and no "
                            + "Destination can be created.", e);
        }
    }
}
