package com.crm.travelcrm.quotation.mapper;

import com.crm.travelcrm.quotation.dto.QuotationRequestDto;
import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
import com.crm.travelcrm.quotation.dto.QuotationSummaryDto;
import com.crm.travelcrm.quotation.entity.*;
import com.crm.travelcrm.quotation.enums.DiscountType;
import com.crm.travelcrm.quotation.enums.QuotationStage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Hand-written mapper for the quotation aggregate — follows the same style as
 * {@code LeadMapper} (a plain Spring {@code @Component}, not MapStruct, because the
 * structure is deeply nested and the request/entity field names differ in places).
 *
 * <p>All entity→DTO copying happens while the Hibernate session is still open
 * (the service calls these from inside a {@code @Transactional} method) so lazy
 * collections initialise correctly with {@code spring.jpa.open-in-view=false}.
 */
@Component
public class QuotationMapper {

    // ════════════════════════════════════════════════════════════════════════
    //  REQUEST  →  ENTITY   (used by create & update; lead resolution + customer
    //  snapshot are handled in the service which owns the LeadRepository)
    // ════════════════════════════════════════════════════════════════════════

    public void applyRequest(QuotationRequestDto req, Quotation q) {
        q.setTitle(req.getTitle() != null && !req.getTitle().isBlank() ? req.getTitle() : "Quotation");
        // version is NOT taken from the request — the service assigns it (per-lead auto
        // numbering on create: v1.0, v2.0, …) and leaves it unchanged on update.
        q.setStage(req.getQuotationStage() != null ? req.getQuotationStage() : QuotationStage.DRAFT);
        q.setCoverImageUrl(req.getCoverImageUrl());
        q.setNotes(req.getNotes());

        applyFlight(req.getFlight(), q);
        applyHotel(req.getHotel(), q);
        applySightseeing(req.getSightseeing(), q);
        applyCruise(req.getCruise(), q);
        applyVehicle(req.getVehicle(), q);
        applyAddon(req.getAddons(), q);

        replaceList(q.getInclusions(), req.getInclusions());
        replaceList(q.getExclusions(), req.getExclusions());
        replaceList(q.getPaymentPolicies(), req.getPaymentPolicies());
        replaceList(q.getCancellationPolicies(), req.getCancellationPolicies());
        replaceList(q.getBookingTerms(), req.getBookingTerms());

        QuotationRequestDto.Pricing p = req.getPricing();
        if (p != null) {
            q.setDiscount(p.getDiscount());
            q.setDiscountType(p.getDiscType() != null ? p.getDiscType() : DiscountType.FIXED);
            q.setTax(p.getTax());
            q.setMarkup(p.getMarkup());
        } else {
            q.setDiscountType(DiscountType.FIXED);
        }
    }

    private void applyFlight(QuotationRequestDto.FlightSection f, Quotation q) {
        q.getFlightSegments().clear();
        if (f == null) {
            q.setFlightIncluded(false);
            q.setFlightAmount(BigDecimal.ZERO);
            return;
        }
        q.setFlightIncluded(f.getIncluded());
        q.setFlightTitle(f.getTitle());
        q.setFlightAmount(f.getAmount());
        q.setFlightJourney(f.getJourney());
        if (f.getSegments() != null) {
            for (QuotationRequestDto.Segment s : f.getSegments()) {
                QuotationFlightSegment seg = QuotationFlightSegment.builder()
                        .airline(s.getAirline())
                        .flightNo(s.getFlightNo())
                        .travelClass(s.getTravelClass())
                        .fromLocation(s.getFrom())
                        .toLocation(s.getTo())
                        .depDate(s.getDepDate())
                        .depTime(s.getDepTime())
                        .arrDate(s.getArrDate())
                        .arrTime(s.getArrTime())
                        .duration(s.getDuration())
                        .cabinBaggage(s.getCabin())
                        .checkinBaggage(s.getCheckin())
                        .pricePerPax(s.getPricePerPax())
                        .pax(s.getPax())
                        .build();
                if (s.getConnections() != null) {
                    for (QuotationRequestDto.Connection c : s.getConnections()) {
                        seg.addConnection(QuotationFlightConnection.builder()
                                .airline(c.getAirline())
                                .flightNo(c.getFlightNo())
                                .fromLocation(c.getFrom())
                                .toLocation(c.getTo())
                                .depDate(c.getDepDate())
                                .depTime(c.getDepTime())
                                .arrDate(c.getArrDate())
                                .arrTime(c.getArrTime())
                                .build());
                    }
                }
                q.addFlightSegment(seg);
            }
        }
    }

    private void applyHotel(QuotationRequestDto.HotelSection h, Quotation q) {
        q.getHotels().clear();
        if (h == null) {
            q.setHotelIncluded(false);
            q.setHotelAmount(BigDecimal.ZERO);
            return;
        }
        q.setHotelIncluded(h.getIncluded());
        q.setHotelTitle(h.getTitle());
        q.setHotelAmount(h.getAmount());
        q.setHotelNotes(h.getNotes());
        if (h.getHotels() != null) {
            for (QuotationRequestDto.HotelItem hi : h.getHotels()) {
                q.addHotel(QuotationHotel.builder()
                        .name(hi.getName())
                        .city(hi.getCity())
                        .checkIn(hi.getCheckIn())
                        .checkOut(hi.getCheckOut())
                        .roomType(hi.getRoomType())
                        .mealPlan(hi.getMealPlan())
                        .refundable(hi.getRefundable())
                        .stars(hi.getStars())
                        .pricePerRoom(hi.getPricePerRoom())
                        .rooms(hi.getRooms())
                        .imagePath(hi.getImagePath())
                        .build());
            }
        }
    }

    private void applySightseeing(QuotationRequestDto.SightseeingSection s, Quotation q) {
        q.getSightseeingDays().clear();
        if (s == null) {
            q.setSightseeingIncluded(false);
            q.setSightseeingAmount(BigDecimal.ZERO);
            return;
        }
        q.setSightseeingIncluded(s.getIncluded());
        q.setSightseeingTitle(s.getTitle());
        q.setSightseeingAmount(s.getAmount());
        q.setSightseeingNotes(s.getNotes());
        if (s.getDays() != null) {
            for (QuotationRequestDto.DayItem d : s.getDays()) {
                QuotationSightseeingDay day = QuotationSightseeingDay.builder()
                        .dayNumber(d.getDay())
                        .date(d.getDate())
                        .pricePerPax(d.getPricePerPax())
                        .pax(d.getPax())
                        .build();
                if (d.getActivities() != null) {
                    for (QuotationRequestDto.Activity a : d.getActivities()) {
                        QuotationSightseeingActivity act = QuotationSightseeingActivity.builder()
                                .attraction(a.getAttraction())
                                .startTime(a.getStartTime())
                                .description(a.getDescription())
                                .transfer(a.getTransfer())
                                .imagePath(a.getImagePath())
                                .build();
                        if (a.getMeals() != null) act.getMeals().addAll(a.getMeals());
                        day.addActivity(act);
                    }
                }
                q.addSightseeingDay(day);
            }
        }
    }

    private void applyCruise(QuotationRequestDto.CruiseSection c, Quotation q) {
        q.getCruises().clear();
        if (c == null) {
            q.setCruiseIncluded(false);
            q.setCruiseAmount(BigDecimal.ZERO);
            return;
        }
        q.setCruiseIncluded(c.getIncluded());
        q.setCruiseTitle(c.getTitle());
        q.setCruiseAmount(c.getAmount());
        if (c.getCruises() != null) {
            for (QuotationRequestDto.CruiseItem ci : c.getCruises()) {
                q.addCruise(QuotationCruise.builder()
                        .name(ci.getName())
                        .type(ci.getType())
                        .depPort(ci.getDepPort())
                        .arrPort(ci.getArrPort())
                        .depDate(ci.getDepDate())
                        .nights(ci.getNights())
                        .cabin(ci.getCabin())
                        .price(ci.getPrice())
                        .pricePerPax(ci.getPricePerPax())
                        .pax(ci.getPax())
                        .build());
            }
        }
    }

    private void applyVehicle(QuotationRequestDto.VehicleSection v, Quotation q) {
        q.getVehicles().clear();
        if (v == null) {
            q.setVehicleIncluded(false);
            q.setVehicleAmount(BigDecimal.ZERO);
            return;
        }
        q.setVehicleIncluded(v.getIncluded());
        q.setVehicleTitle(v.getTitle());
        q.setVehicleAmount(v.getAmount());
        if (v.getVehicles() != null) {
            for (QuotationRequestDto.VehicleItem vi : v.getVehicles()) {
                q.addVehicle(QuotationVehicle.builder()
                        .type(vi.getType())
                        .pickup(vi.getPickup())
                        .drop(vi.getDrop())
                        .startDate(vi.getStartDate())
                        .endDate(vi.getEndDate())
                        .price(vi.getPrice())
                        .pricePerVehicle(vi.getPricePerVehicle())
                        .qty(vi.getQty())
                        .notes(vi.getNotes())
                        .build());
            }
        }
    }

    private void applyAddon(QuotationRequestDto.AddonSection a, Quotation q) {
        q.getAddons().clear();
        if (a == null) {
            q.setAddonIncluded(false);
            q.setAddonAmount(BigDecimal.ZERO);
            return;
        }
        q.setAddonIncluded(a.getIncluded());
        q.setAddonTitle(a.getTitle());
        q.setAddonAmount(a.getAmount());
        if (a.getItems() != null) {
            for (QuotationRequestDto.AddonItem it : a.getItems()) {
                q.addAddon(QuotationAddon.builder()
                        .serviceType(it.getServiceType())
                        .description(it.getDescription())
                        .quantity(it.getQuantity())
                        .pricePerUnit(it.getPricePerUnit())
                        .included(it.getIncluded())
                        .build());
            }
        }
    }

    private void replaceList(List<String> target, List<String> source) {
        target.clear();
        if (source != null) {
            for (String s : source) {
                if (s != null && !s.isBlank()) target.add(s);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  ENTITY  →  RESPONSE
    // ════════════════════════════════════════════════════════════════════════

    public QuotationResponseDto toResponse(Quotation q) {
        Integer nights = computeNights(q);
        Integer days = computeDays(q, nights);
        Integer rooms = computeRooms(q);

        return QuotationResponseDto.builder()

                .publicId(q.getPublicId())
                .leadId(q.getLeadPublicId())
                .title(q.getTitle())
                .version(q.getVersion())
                .versionNumber(q.getVersionNumber())
                .quoteNo(q.getQuoteNo())
                .nights(nights)
                .days(days)
                .rooms(rooms)
                .pdfUrl(q.getPdfUrl())
                .quotationStage(q.getStage())
                .leadStage(q.getLeadStage())
                .coverImageUrl(q.getCoverImageUrl())
                .notes(q.getNotes())
                .customer(QuotationResponseDto.Customer.builder()
                        .name(q.getCustomerName())
                        .phone(q.getCustomerPhone())
                        .email(q.getCustomerEmail())
                        .destination(q.getDestination())
                        .travelDate(q.getTravelDate())
                        .adults(q.getAdults())
                        .children(q.getChildren())
                        .infants(q.getInfants())
                        .build())
                .flight(QuotationResponseDto.FlightSection.builder()
                        .included(q.getFlightIncluded())
                        .title(q.getFlightTitle())
                        .amount(q.getFlightAmount())
                        .journey(q.getFlightJourney())
                        .segments(q.getFlightSegments().stream().map(this::toSegment).toList())
                        .build())
                .hotel(QuotationResponseDto.HotelSection.builder()
                        .included(q.getHotelIncluded())
                        .title(q.getHotelTitle())
                        .amount(q.getHotelAmount())
                        .notes(q.getHotelNotes())
                        .hotels(q.getHotels().stream().map(this::toHotel).toList())
                        .build())
                .sightseeing(QuotationResponseDto.SightseeingSection.builder()
                        .included(q.getSightseeingIncluded())
                        .title(q.getSightseeingTitle())
                        .amount(q.getSightseeingAmount())
                        .notes(q.getSightseeingNotes())
                        .days(q.getSightseeingDays().stream().map(this::toDay).toList())
                        .build())
                .cruise(QuotationResponseDto.CruiseSection.builder()
                        .included(q.getCruiseIncluded())
                        .title(q.getCruiseTitle())
                        .amount(q.getCruiseAmount())
                        .cruises(q.getCruises().stream().map(this::toCruise).toList())
                        .build())
                .vehicle(QuotationResponseDto.VehicleSection.builder()
                        .included(q.getVehicleIncluded())
                        .title(q.getVehicleTitle())
                        .amount(q.getVehicleAmount())
                        .vehicles(q.getVehicles().stream().map(this::toVehicle).toList())
                        .build())
                .addons(QuotationResponseDto.AddonSection.builder()
                        .included(q.getAddonIncluded())
                        .title(q.getAddonTitle())
                        .amount(q.getAddonAmount())
                        .items(q.getAddons().stream().map(this::toAddon).toList())
                        .build())
                .inclusions(new ArrayList<>(q.getInclusions()))
                .exclusions(new ArrayList<>(q.getExclusions()))
                .paymentPolicies(new ArrayList<>(q.getPaymentPolicies()))
                .cancellationPolicies(new ArrayList<>(q.getCancellationPolicies()))
                .bookingTerms(new ArrayList<>(q.getBookingTerms()))
                .pricing(QuotationResponseDto.Pricing.builder()
                        .discount(q.getDiscount())
                        .discType(q.getDiscountType())
                        .tax(q.getTax())
                        .markup(q.getMarkup())
                        .build())
                .totals(computeTotals(q))
                .createdBy(q.getCreatedBy())
                .createdAt(q.getCreatedAt())
                .updatedAt(q.getUpdatedAt())
                .build();
    }

    private QuotationResponseDto.Segment toSegment(QuotationFlightSegment s) {
        return QuotationResponseDto.Segment.builder()
                .airline(s.getAirline())
                .flightNo(s.getFlightNo())
                .travelClass(s.getTravelClass())
                .from(s.getFromLocation())
                .to(s.getToLocation())
                .depDate(s.getDepDate())
                .depTime(s.getDepTime())
                .arrDate(s.getArrDate())
                .arrTime(s.getArrTime())
                .duration(s.getDuration())
                .cabin(s.getCabinBaggage())
                .checkin(s.getCheckinBaggage())
                .pricePerPax(s.getPricePerPax())
                .pax(s.getPax())
                .connections(s.getConnections().stream().map(this::toConnection).toList())
                .build();
    }

    private QuotationResponseDto.Connection toConnection(QuotationFlightConnection c) {
        return QuotationResponseDto.Connection.builder()
                .airline(c.getAirline())
                .flightNo(c.getFlightNo())
                .from(c.getFromLocation())
                .to(c.getToLocation())
                .depDate(c.getDepDate())
                .depTime(c.getDepTime())
                .arrDate(c.getArrDate())
                .arrTime(c.getArrTime())
                .build();
    }

    private QuotationResponseDto.HotelItem toHotel(QuotationHotel h) {
        return QuotationResponseDto.HotelItem.builder()
                .name(h.getName())
                .city(h.getCity())
                .checkIn(h.getCheckIn())
                .checkOut(h.getCheckOut())
                .roomType(h.getRoomType())
                .mealPlan(h.getMealPlan())
                .refundable(h.getRefundable())
                .stars(h.getStars())
                .pricePerRoom(h.getPricePerRoom())
                .rooms(h.getRooms())
                .imagePath(h.getImagePath())
                .build();
    }

    private QuotationResponseDto.DayItem toDay(QuotationSightseeingDay d) {
        return QuotationResponseDto.DayItem.builder()
                .day(d.getDayNumber())
                .date(d.getDate())
                .pricePerPax(d.getPricePerPax())
                .pax(d.getPax())
                .activities(d.getActivities().stream().map(this::toActivity).toList())
                .build();
    }

    private QuotationResponseDto.Activity toActivity(QuotationSightseeingActivity a) {
        return QuotationResponseDto.Activity.builder()
                .attraction(a.getAttraction())
                .startTime(a.getStartTime())
                .description(a.getDescription())
                .meals(new ArrayList<>(a.getMeals()))
                .transfer(a.getTransfer())
                .imagePath(a.getImagePath())
                .build();
    }

    private QuotationResponseDto.CruiseItem toCruise(QuotationCruise c) {
        return QuotationResponseDto.CruiseItem.builder()
                .name(c.getName())
                .type(c.getType())
                .depPort(c.getDepPort())
                .arrPort(c.getArrPort())
                .depDate(c.getDepDate())
                .nights(c.getNights())
                .cabin(c.getCabin())
                .price(c.getPrice())
                .pricePerPax(c.getPricePerPax())
                .pax(c.getPax())
                .build();
    }

    private QuotationResponseDto.VehicleItem toVehicle(QuotationVehicle v) {
        return QuotationResponseDto.VehicleItem.builder()
                .type(v.getType())
                .pickup(v.getPickup())
                .drop(v.getDrop())
                .startDate(v.getStartDate())
                .endDate(v.getEndDate())
                .price(v.getPrice())
                .pricePerVehicle(v.getPricePerVehicle())
                .qty(v.getQty())
                .notes(v.getNotes())
                .build();
    }

    private QuotationResponseDto.AddonItem toAddon(QuotationAddon a) {
        return QuotationResponseDto.AddonItem.builder()
                .serviceType(a.getServiceType())
                .description(a.getDescription())
                .quantity(a.getQuantity())
                .pricePerUnit(a.getPricePerUnit())
                .included(a.getIncluded())
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  ENTITY  →  SUMMARY
    // ════════════════════════════════════════════════════════════════════════

    public QuotationSummaryDto toSummary(Quotation q) {
        return QuotationSummaryDto.builder()
                .publicId(q.getPublicId())
                .leadId(q.getLeadPublicId())
                .title(q.getTitle())
                .version(q.getVersion())
                .versionNumber(q.getVersionNumber())
                .pdfUrl(q.getPdfUrl())
                .quotationStage(q.getStage())
                .leadStage(q.getLeadStage())
                .customerName(q.getCustomerName())
                .destination(q.getDestination())
                .travelDate(q.getTravelDate())
                .grandTotal(computeTotals(q).getGrandTotal())
                .createdAt(q.getCreatedAt())
                .updatedAt(q.getUpdatedAt())
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PRICING — mirrors the frontend Summarypricingtab.jsx exactly:
    //    subtotal   = sum of the six section amounts
    //    discAmt    = discType == % ? subtotal * disc / 100 : disc
    //    afterDisc  = subtotal - discAmt + markup
    //    taxAmt     = afterDisc * tax / 100
    //    grandTotal = afterDisc + taxAmt
    // ════════════════════════════════════════════════════════════════════════

    public QuotationResponseDto.Totals computeTotals(Quotation q) {
        BigDecimal subtotal = nz(q.getFlightAmount())
                .add(nz(q.getHotelAmount()))
                .add(nz(q.getSightseeingAmount()))
                .add(nz(q.getCruiseAmount()))
                .add(nz(q.getVehicleAmount()))
                .add(nz(q.getAddonAmount()))
                .setScale(2, RoundingMode.HALF_UP);

        DiscountType dt = q.getDiscountType() != null ? q.getDiscountType() : DiscountType.FIXED;
        BigDecimal discountRaw = nz(q.getDiscount());
        BigDecimal discountAmount = dt == DiscountType.PERCENT
                ? subtotal.multiply(discountRaw).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : discountRaw.setScale(2, RoundingMode.HALF_UP);

        BigDecimal markup = nz(q.getMarkup()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal afterDiscount = subtotal.subtract(discountAmount).add(markup);

        BigDecimal taxPercent = nz(q.getTax());
        BigDecimal taxAmount = afterDiscount.multiply(taxPercent)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        BigDecimal grandTotal = afterDiscount.add(taxAmount).setScale(2, RoundingMode.HALF_UP);

        Integer adults = q.getAdults();
        BigDecimal perAdult = (adults != null && adults > 0)
                ? grandTotal.divide(BigDecimal.valueOf(adults), 2, RoundingMode.HALF_UP)
                : null;

        return QuotationResponseDto.Totals.builder()
                .subtotal(subtotal)
                .discountType(dt)
                .discount(discountRaw)
                .discountAmount(discountAmount)
                .markup(markup)
                .taxPercent(taxPercent)
                .taxAmount(taxAmount)
                .grandTotal(grandTotal)
                .addonsTotal(nz(q.getAddonAmount()).setScale(2, RoundingMode.HALF_UP))
                .perAdult(perAdult)
                .build();
    }

    // ── Derived trip-shape helpers (computed at read time, not stored) ──────────

    /** Total room-nights across all hotels, parsing ISO check-in/out dates. */
    private Integer computeNights(Quotation q) {
        if (q.getHotels() == null || q.getHotels().isEmpty()) return null;
        int nights = 0;
        for (QuotationHotel h : q.getHotels()) {
            nights += nightsBetween(h.getCheckIn(), h.getCheckOut());
        }
        return nights > 0 ? nights : null;
    }

    /** Trip length: nights + 1 when known, else the sightseeing day count. */
    private Integer computeDays(Quotation q, Integer nights) {
        if (nights != null && nights > 0) return nights + 1;
        int dayCount = q.getSightseeingDays() != null ? q.getSightseeingDays().size() : 0;
        return dayCount > 0 ? dayCount : null;
    }

    /** Total rooms summed across all hotels. */
    private Integer computeRooms(Quotation q) {
        if (q.getHotels() == null || q.getHotels().isEmpty()) return null;
        int rooms = q.getHotels().stream()
                .mapToInt(h -> h.getRooms() != null ? h.getRooms() : 0)
                .sum();
        return rooms > 0 ? rooms : null;
    }

    private static int nightsBetween(String checkIn, String checkOut) {
        if (checkIn == null || checkOut == null || checkIn.isBlank() || checkOut.isBlank()) return 0;
        try {
            long d = ChronoUnit.DAYS.between(LocalDate.parse(checkIn.trim()), LocalDate.parse(checkOut.trim()));
            return d > 0 ? (int) d : 0;
        } catch (Exception ignored) {
            return 0;   // non-ISO or malformed dates contribute nothing
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return Optional.ofNullable(v).orElse(BigDecimal.ZERO);
    }
}