package com.crm.travelcrm.hotelmarketplace;

import com.crm.travelcrm.hotelmarketplace.booking.dto.MarketplaceBookingAdminDto;
import com.crm.travelcrm.hotelmarketplace.booking.dto.MarketplaceBookingTenantDto;
import com.crm.travelcrm.hotelmarketplace.booking.entity.PlatformHotelBooking;
import com.crm.travelcrm.hotelmarketplace.booking.mapper.MarketplaceBookingMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform's own economics must never reach a tenant.
 *
 * <p>{@code supplierTotal} is what the platform pays the hotel and {@code platformEarning} is what it
 * keeps. From the tenant's side the platform is simply the counterparty they owe; the margin inside
 * that price is not part of the relationship, and a tenant who can see the net rate can go around the
 * platform to the hotel (design §5.11, §10.4, §11).</p>
 *
 * <p>This is a <b>structural</b> test rather than a serialisation one, and deliberately so. The
 * defence the design chose is two separate DTO classes — not {@code @JsonIgnore} on one — precisely
 * because an annotation is one careless edit away from a leak the compiler cannot catch. A test that
 * only checked the current JSON would pass the moment somebody added the field back and forgot the
 * annotation. This one fails on the field existing at all.</p>
 */
class MarketplaceMoneyVisibilityTest {

    /** Names that carry the platform's own economics, in any casing a field might use. */
    private static final List<String> PLATFORM_ONLY = List.of(
            "suppliertotal", "platformearning", "revisedsuppliertotal",
            "internalnotes", "retainedplatformearning");

    @Test
    @DisplayName("the tenant DTO has no field for supplier cost or platform earning")
    void tenantDtoCannotCarryPlatformEconomics() {
        Set<String> fields = fieldNames(MarketplaceBookingTenantDto.class);

        assertThat(fields)
                .as("A tenant must never be able to read what the platform paid the hotel, "
                        + "what it kept, or the operator's internal notes. Adding one of these to "
                        + "MarketplaceBookingTenantDto leaks it to every tenant on the platform.")
                .doesNotContainAnyElementsOf(PLATFORM_ONLY);
    }

    @Test
    @DisplayName("the SuperAdmin DTO does carry them — the split is deliberate, not an oversight")
    void adminDtoDoesCarryThem() {
        Set<String> fields = fieldNames(MarketplaceBookingAdminDto.class);

        assertThat(fields).contains("suppliertotal", "platformearning", "internalnotes");
    }

    @Test
    @DisplayName("the tenant mapper populates the payable but has nothing to copy the margin into")
    void mapperCannotLeakWhatTheDtoCannotHold() {
        PlatformHotelBooking booking = new PlatformHotelBooking();
        booking.setPublicId(UUID.randomUUID());
        booking.setBookingCode("MKT-2026-DEADBEEF");
        booking.setHotelNameSnapshot("Taj Exotica");
        booking.setSupplierTotal(new BigDecimal("4000.00"));
        booking.setPlatformEarning(new BigDecimal("400.00"));
        booking.setTenantPayable(new BigDecimal("4400.00"));
        booking.setInternalNotes("Supplier gave us 12% — push for 15 next quarter");

        MarketplaceBookingTenantDto dto =
                new MarketplaceBookingMapper().toTenantDto(booking, "BKG-0001");

        assertThat(dto.getTenantPayable()).isEqualByComparingTo("4400.00");
        // Nothing to assert absent: there is no getter to call. That IS the assertion, and the two
        // structural tests above are what keep it true.
        assertThat(fieldNames(dto.getClass())).doesNotContainAnyElementsOf(PLATFORM_ONLY);
    }

    private static Set<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .map(Field::getName)
                .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toSet());
    }
}
