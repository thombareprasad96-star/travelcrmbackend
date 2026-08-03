package com.crm.travelcrm.customer.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;

import java.util.UUID;

/**
 * The {@code customer} block of a create-booking payload: <em>which</em> customer this booking is
 * for, expressed as one of two mutually exclusive modes.
 *
 * <ul>
 *   <li><b>Existing</b> — {@code customerPublicId} of a customer the phone search already found,
 *       optionally with a {@code sync} patch when the agent corrected details on the form.</li>
 *   <li><b>New</b> — a full {@code newCustomer} profile, used when the phone search returned
 *       nothing.</li>
 * </ul>
 *
 * <p>Lives in the customer module rather than the booking module on purpose: it is the input
 * contract of {@code CustomerService.resolveOrCreate}, and booking merely embeds it. That keeps the
 * dependency pointing one way (booking depends on customer) instead of the customer module having
 * to import a booking DTO to describe its own operation.
 *
 * <p>The "exactly one mode" rule is a class-level {@code @AssertTrue} so a malformed block produces
 * one clear 400 naming the problem, rather than a null-pointer somewhere down in the service or —
 * worse — a silent fallthrough that invents a customer nobody asked for.
 */
@Data
public class CustomerResolveRequest {

    /**
     * publicId of an already-known customer. Note the frontend reads this off {@code CustomerResponse.id},
     * which carries the publicId UUID (the internal Long is never exposed).
     */
    private UUID customerPublicId;

    /** Profile for a customer that does not exist yet. Mutually exclusive with the field above. */
    @Valid
    private CreateCustomerRequest newCustomer;

    /** Optional corrections to apply to the existing customer. Meaningless without a publicId. */
    @Valid
    private CustomerSyncRequest sync;

    @JsonIgnore
    public boolean isExistingMode() {
        return customerPublicId != null;
    }

    @AssertTrue(message = "Provide either an existing customerPublicId or a newCustomer block, not both")
    @JsonIgnore
    public boolean isExactlyOneMode() {
        return (customerPublicId != null) ^ (newCustomer != null);
    }

    @AssertTrue(message = "Customer details can only be synced onto an existing customer")
    @JsonIgnore
    public boolean isSyncOnlyWithExisting() {
        // A `sync` sent alongside `newCustomer` means the caller is confused about which mode it is
        // in. Rejecting it is kinder than silently ignoring it, which would look like the edits were
        // saved when nothing was written.
        return sync == null || customerPublicId != null;
    }
}
