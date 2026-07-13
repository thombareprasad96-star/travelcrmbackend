package com.crm.travelcrm.booking.cancellation.enums;

/**
 * How a cancellation band's charge is expressed.
 *
 * <p>{@link #PERCENT} is stored as a whole-number percent in [0, 100] (e.g. {@code 50} = 50%);
 * the calculator divides by 100. {@link #FLAT} is an absolute amount in the same single currency
 * as every other money figure in this codebase. Either way the computed charge is clamped to the
 * booking's base fare so a cancellation charge can never exceed what the customer contracted to owe.
 */
public enum DeductionType {
    PERCENT,
    FLAT
}