package com.crm.travelcrm.accounting.tax.service;

import com.crm.travelcrm.accounting.support.GstStateCodes;
import com.crm.travelcrm.accounting.tax.enums.SupplyType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Resolves the two state codes that drive GST split. The <b>supplier</b> state is taken from the
 * supplier GSTIN (chars 1–2, authoritative) with the Company's state name as a fallback. The
 * <b>place of supply</b> is the recipient's state code (from an explicit override, the recipient
 * GSTIN, or the customer's state name, in that order). Intra-state when they match, inter-state
 * otherwise (including when the place of supply cannot be determined — the safe GST default is IGST).
 */
@Service
public class PlaceOfSupplyResolver {

    /** The supplier's 2-digit GST state code — from GSTIN first, then the state name. */
    public String supplierStateCode(String supplierGstin, String supplierStateName) {
        String fromGstin = GstStateCodes.fromGstin(supplierGstin);
        if (fromGstin != null) return fromGstin;
        return GstStateCodes.fromStateName(supplierStateName);
    }

    /** The place-of-supply state code — explicit override, then recipient GSTIN, then state name. */
    public String placeOfSupplyCode(String override, String recipientGstin, String recipientStateName) {
        if (StringUtils.hasText(override)) {
            String o = override.trim();
            if (o.length() == 2 && o.chars().allMatch(Character::isDigit)) return o;
            String byName = GstStateCodes.fromStateName(o);
            if (byName != null) return byName;
        }
        String fromGstin = GstStateCodes.fromGstin(recipientGstin);
        if (fromGstin != null) return fromGstin;
        return GstStateCodes.fromStateName(recipientStateName);
    }

    /** INTRA when both codes are known and equal; INTER otherwise (safe GST default). */
    public SupplyType supplyType(String supplierStateCode, String placeOfSupplyCode) {
        if (StringUtils.hasText(supplierStateCode)
                && supplierStateCode.equals(placeOfSupplyCode)) {
            return SupplyType.INTRA_STATE;
        }
        return SupplyType.INTER_STATE;
    }
}