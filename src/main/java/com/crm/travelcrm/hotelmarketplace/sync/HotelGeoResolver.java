package com.crm.travelcrm.hotelmarketplace.sync;

import com.crm.travelcrm.hotelmarketplace.catalog.entity.PlatformHotel;
import com.crm.travelcrm.master.geography.entity.City;
import com.crm.travelcrm.master.geography.entity.Country;
import com.crm.travelcrm.master.geography.repository.CityRepository;
import com.crm.travelcrm.master.geography.repository.CountryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Maps a catalog hotel's flat geography (ISO country code + city name) onto a row in ONE tenant's
 * own {@code City} master.
 *
 * <p>This exists because the two sides model geography differently and neither can be changed. The
 * catalog is global, so it cannot reference {@code City} — that table is tenant-scoped. The tenant's
 * {@code Hotel.city} is a NOT NULL foreign key, so a projection cannot exist without a real city
 * row. Something has to bridge them, and it has to be allowed to fail.</p>
 *
 * <p><b>It resolves; it never creates and it never guesses.</b> Two rules, both learned the hard
 * way:</p>
 * <ul>
 *   <li><b>Country-qualified.</b> City names repeat across countries — Hyderabad, Birmingham,
 *       Santiago. Matching on name alone eventually files an Indian hotel under a Pakistani city.</li>
 *   <li><b>No "first city under the destination" fallback.</b> That is the failure mode design doc
 *       §6.5 calls out by name: it always succeeds, so nothing ever looks broken, and the hotel is
 *       simply in the wrong place on every screen and every voucher from then on.</li>
 * </ul>
 *
 * <p>An empty result is a legitimate, expected outcome. The caller decides what to do with it —
 * refuse a first import with an actionable message, or leave an existing projection where it is and
 * flag it {@code LOCATION_MAPPING_REQUIRED}. What the caller must never do is pick a city anyway.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotelGeoResolver {

    private final CountryRepository countryRepository;
    private final CityRepository cityRepository;

    /**
     * The tenant city this catalog hotel belongs in, if it can be determined safely.
     *
     * @return the matched city, or empty when the tenant has no country for the code, or no city of
     *         that name under it
     */
    public Optional<City> resolve(PlatformHotel hotel, Long tenantId) {
        String cityName = hotel.getCityName();
        if (cityName == null || cityName.isBlank()) {
            return Optional.empty();
        }

        // Country first, by ISO code — the one identifier that is stable across spellings
        // ("India" / "INDIA" / "Republic of India" are all IN).
        Optional<Country> country = hotel.getCountryCode() == null || hotel.getCountryCode().isBlank()
                ? Optional.empty()
                : countryRepository.findByTenantIdAndCode(tenantId, hotel.getCountryCode().trim().toUpperCase());

        if (country.isEmpty()) {
            log.debug("Geo resolve miss: tenant {} has no country for code '{}' (hotel {})",
                    tenantId, hotel.getCountryCode(), hotel.getPublicId());
            return Optional.empty();
        }

        Optional<City> city = cityRepository
                .findFirstByTenantIdAndCountryIdAndNameIgnoreCaseOrderByIdAsc(
                        tenantId, country.get().getId(), cityName.trim());

        if (city.isEmpty()) {
            log.debug("Geo resolve miss: tenant {} has no city '{}' under country {} (hotel {})",
                    tenantId, cityName, country.get().getCode(), hotel.getPublicId());
        }
        return city;
    }

    /**
     * What the tenant must create for {@link #resolve} to succeed. Used verbatim in the error a
     * failed import returns, so the message names the actual missing row rather than "not found".
     */
    public String describeMissingGeography(PlatformHotel hotel, Long tenantId) {
        String code = hotel.getCountryCode();
        boolean hasCountry = code != null && !code.isBlank()
                && countryRepository.findByTenantIdAndCode(tenantId, code.trim().toUpperCase()).isPresent();

        if (code == null || code.isBlank()) {
            return "This hotel has no country code in the catalog, so it cannot be placed in your "
                    + "geography. Ask the platform administrator to set one.";
        }
        if (!hasCountry) {
            return "Add country '" + code + "' to your masters, then a city named '"
                    + hotel.getCityName() + "' under it, and import again.";
        }
        return "Add a city named '" + hotel.getCityName() + "' under country '" + code
                + "' in your masters, then import again.";
    }
}
