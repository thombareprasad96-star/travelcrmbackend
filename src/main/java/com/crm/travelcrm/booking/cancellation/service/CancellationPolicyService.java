package com.crm.travelcrm.booking.cancellation.service;

import com.crm.travelcrm.booking.cancellation.dto.CancellationPolicyResponse;
import com.crm.travelcrm.booking.cancellation.dto.CreateCancellationPolicyRequest;
import com.crm.travelcrm.booking.cancellation.enums.CancellationPolicyLevel;

import java.util.List;
import java.util.UUID;

public interface CancellationPolicyService {

    /** Create a policy, or — if one already exists for the (level, owner) — publish a new immutable version. */
    CancellationPolicyResponse save(CreateCancellationPolicyRequest request);

    /** The currently-active policies for the tenant (company default + any package policies). */
    List<CancellationPolicyResponse> listActive();

    CancellationPolicyResponse getByPublicId(UUID publicId);

    /** Full version history for one logical policy (company default when ownerPublicId is null). */
    List<CancellationPolicyResponse> history(CancellationPolicyLevel level, UUID ownerPublicId);

    /** Retire a version (soft delete). Blocked when a non-cancelled booking still pins it. */
    void delete(UUID publicId);
}