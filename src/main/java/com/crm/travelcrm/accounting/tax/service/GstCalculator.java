package com.crm.travelcrm.accounting.tax.service;

import com.crm.travelcrm.accounting.tax.dto.GstLineInput;
import com.crm.travelcrm.accounting.tax.dto.GstQuote;
import com.crm.travelcrm.accounting.tax.enums.SupplyType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure GST math — no persistence, no I/O. For each taxable line it computes the GST amount and splits
 * it by supply type: intra-state → CGST + SGST (each half the rate), inter-state → IGST (the full
 * rate). The SGST half is derived as {@code gst − cgst} so the two halves always sum back to the exact
 * GST amount with no rounding drift. When {@code applyGst} is false (Bill of Supply / non-GST invoice)
 * every tax component is zero and the lines carry taxable value only.
 *
 * <p>Mirrors the {@code CancellationCalculator} precedent: a stateless Spring bean returning an
 * immutable {@link GstQuote} value object.
 */
@Service
public class GstCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO = new BigDecimal("2");

    public GstQuote calculate(List<GstLineInput> inputs, SupplyType supplyType, boolean applyGst) {
        List<GstQuote.Line> lines = new ArrayList<>();
        BigDecimal totalTaxable = BigDecimal.ZERO;
        BigDecimal totalCgst = BigDecimal.ZERO;
        BigDecimal totalSgst = BigDecimal.ZERO;
        BigDecimal totalIgst = BigDecimal.ZERO;
        BigDecimal totalCess = BigDecimal.ZERO;

        for (GstLineInput in : inputs) {
            BigDecimal taxable = money(in.getTaxableValue());
            BigDecimal ratePct = applyGst ? nz(in.getGstRatePct()) : BigDecimal.ZERO;
            BigDecimal cessPct = applyGst ? nz(in.getCessPct()) : BigDecimal.ZERO;

            BigDecimal gstAmt = money(taxable.multiply(ratePct).divide(HUNDRED, 4, RoundingMode.HALF_UP));
            BigDecimal cessAmt = money(taxable.multiply(cessPct).divide(HUNDRED, 4, RoundingMode.HALF_UP));

            BigDecimal cgst = BigDecimal.ZERO, sgst = BigDecimal.ZERO, igst = BigDecimal.ZERO;
            if (supplyType == SupplyType.INTRA_STATE) {
                cgst = money(gstAmt.divide(TWO, 2, RoundingMode.HALF_UP));
                sgst = gstAmt.subtract(cgst);   // remainder → halves always re-sum to gstAmt
            } else {
                igst = gstAmt;
            }

            BigDecimal lineTotal = taxable.add(cgst).add(sgst).add(igst).add(cessAmt);

            lines.add(GstQuote.Line.builder()
                    .description(in.getDescription())
                    .hsnSac(in.getHsnSac())
                    .taxableValue(taxable)
                    .gstRatePct(ratePct)
                    .cgstAmt(cgst).sgstAmt(sgst).igstAmt(igst).cessAmt(cessAmt)
                    .lineTotal(lineTotal)
                    .itcEligible(in.isItcEligible())
                    .serviceItemId(in.getServiceItemId())
                    .serviceItemPublicId(in.getServiceItemPublicId())
                    .build());

            totalTaxable = totalTaxable.add(taxable);
            totalCgst = totalCgst.add(cgst);
            totalSgst = totalSgst.add(sgst);
            totalIgst = totalIgst.add(igst);
            totalCess = totalCess.add(cessAmt);
        }

        BigDecimal totalWithGst = totalTaxable.add(totalCgst).add(totalSgst).add(totalIgst).add(totalCess);

        return GstQuote.builder()
                .supplyType(supplyType)
                .lines(lines)
                .totalTaxable(totalTaxable)
                .totalCgst(totalCgst)
                .totalSgst(totalSgst)
                .totalIgst(totalIgst)
                .totalCess(totalCess)
                .totalWithGst(totalWithGst)
                .build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static BigDecimal money(BigDecimal v) {
        return (v != null ? v : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}