package com.crm.travelcrm.quotationtemplate.service;

import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.quotation.dto.QuotationRequestDto;
import com.crm.travelcrm.quotation.enums.QuotationStage;
import com.crm.travelcrm.quotationtemplate.entity.QuotationTemplate;
import com.crm.travelcrm.quotationtemplate.entity.QuotationTemplateHotel;
import com.crm.travelcrm.quotationtemplate.entity.QuotationTemplateItinerary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a template plus a lead into the exact payload the quotation builder already speaks.
 *
 * <p>This is why "clone then edit" needed no changes to the quotation module: rather than
 * hand-rolling a second deep-copy, the template is rendered as a {@code QuotationRequestDto} and
 * handed to {@code QuotationService.create}, which then applies the versioning, the per-tenant quote
 * number, the customer snapshot and the totals exactly as it would for a hand-built quotation. The
 * agent lands in the normal builder on a normal DRAFT and edits it like any other.
 *
 * <p>Two things the template cannot know until a lead is attached, and which are computed here:
 * <ul>
 *   <li><b>Dates.</b> Hotel check-in/check-out are walked forward from {@code Lead.travelDate},
 *       hotel by hotel, using each row's nights. No travel date ⇒ the fields are left blank rather
 *       than invented.</li>
 *   <li><b>Party size.</b> Rooms fall back to the lead's room count and pax to adults + children,
 *       so a 5-room family gets a 5-room quotation off a template authored for two.</li>
 * </ul>
 *
 * <p>Pricing adjustments (discount, tax, markup) are deliberately not carried across: the template's
 * {@code basePrice} is an indicative figure for matching and display, while a quotation's totals are
 * always recomputed from its own line items.
 */
@Component
public class TemplateQuotationAssembler {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    public QuotationRequestDto toQuotationRequest(QuotationTemplate template, Lead lead, String titleOverride) {
        QuotationRequestDto dto = new QuotationRequestDto();
        dto.setLeadId(lead.getPublicId());
        dto.setTitle(StringUtils.hasText(titleOverride) ? titleOverride.trim() : template.getName());
        dto.setQuotationStage(QuotationStage.DRAFT);
        dto.setCoverImageUrl(template.getCoverImageUrl());
        dto.setNotes(template.getDescription());

        int pax = Math.max(1, nz(lead.getAdults()) + nz(lead.getChildren()));
        int defaultRooms = Math.max(1, nz(lead.getRooms()));

        dto.setHotel(buildHotelSection(template, lead.getTravelDate(), defaultRooms));
        dto.setSightseeing(buildSightseeingSection(template, lead.getTravelDate(), pax));
        dto.setInclusions(List.copyOf(template.getInclusions()));
        dto.setExclusions(List.copyOf(template.getExclusions()));
        return dto;
    }

    // ── Hotels ────────────────────────────────────────────────────────────────

    private QuotationRequestDto.HotelSection buildHotelSection(
            QuotationTemplate template, LocalDate travelDate, int defaultRooms) {

        List<QuotationRequestDto.HotelItem> items = new ArrayList<>();
        BigDecimal amount = BigDecimal.ZERO;
        LocalDate cursor = travelDate;

        for (QuotationTemplateHotel h : template.getHotels()) {
            int nights = positiveOr(h.getNights(), 1);
            int rooms = positiveOr(h.getRooms(), defaultRooms);

            QuotationRequestDto.HotelItem item = new QuotationRequestDto.HotelItem();
            item.setName(h.getName());
            item.setCity(h.getCity());
            item.setStars(h.getStars());
            item.setRoomType(h.getRoomType());
            item.setMealPlan(h.getMealPlan());
            item.setRefundable(h.getRefundable());
            item.setPricePerRoom(h.getPricePerRoom());
            item.setRooms(rooms);
            item.setImagePath(h.getImagePath());

            // The cursor advances even when the price is blank — a free night is still a night.
            if (cursor != null) {
                item.setCheckIn(cursor.format(ISO));
                cursor = cursor.plusDays(nights);
                item.setCheckOut(cursor.format(ISO));
            }
            if (h.getPricePerRoom() != null) {
                amount = amount.add(h.getPricePerRoom()
                        .multiply(BigDecimal.valueOf(rooms))
                        .multiply(BigDecimal.valueOf(nights)));
            }
            items.add(item);
        }

        QuotationRequestDto.HotelSection section = new QuotationRequestDto.HotelSection();
        section.setIncluded(!items.isEmpty());
        section.setTitle("Accommodation");
        section.setAmount(amount);
        section.setNotes(null);
        section.setHotels(items);
        return section;
    }

    // ── Sightseeing (the itinerary becomes the day plan) ──────────────────────

    private QuotationRequestDto.SightseeingSection buildSightseeingSection(
            QuotationTemplate template, LocalDate travelDate, int pax) {

        List<QuotationRequestDto.DayItem> days = new ArrayList<>();
        BigDecimal amount = BigDecimal.ZERO;

        for (QuotationTemplateItinerary day : template.getItinerary()) {
            boolean hasNarrative = StringUtils.hasText(day.getTitle()) || StringUtils.hasText(day.getDescription());
            // A day that is neither described nor priced would land in the builder as an empty row.
            if (!hasNarrative && day.getPricePerPax() == null) continue;

            QuotationRequestDto.DayItem item = new QuotationRequestDto.DayItem();
            item.setDay(day.getDayNumber());
            if (travelDate != null && day.getDayNumber() != null && day.getDayNumber() >= 1) {
                item.setDate(travelDate.plusDays(day.getDayNumber() - 1L).format(ISO));
            }
            item.setPricePerPax(day.getPricePerPax());
            item.setPax(pax);

            if (hasNarrative) {
                QuotationRequestDto.Activity activity = new QuotationRequestDto.Activity();
                activity.setAttraction(StringUtils.hasText(day.getTitle()) ? day.getTitle() : day.getCityName());
                activity.setDescription(day.getDescription());
                item.setActivities(List.of(activity));
            } else {
                item.setActivities(List.of());
            }

            if (day.getPricePerPax() != null) {
                amount = amount.add(day.getPricePerPax().multiply(BigDecimal.valueOf(pax)));
            }
            days.add(item);
        }

        QuotationRequestDto.SightseeingSection section = new QuotationRequestDto.SightseeingSection();
        section.setIncluded(!days.isEmpty());
        section.setTitle("Day-wise Itinerary");
        section.setAmount(amount);
        section.setNotes(null);
        section.setDays(days);
        return section;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static int positiveOr(Integer v, int fallback) {
        return v != null && v > 0 ? v : fallback;
    }
}