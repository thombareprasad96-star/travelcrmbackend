package com.crm.travelcrm.booking.cancellation.dto;

import com.crm.travelcrm.booking.cancellation.enums.CancellationPolicyLevel;
import lombok.*;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A cancellation policy version as returned to the client. Never exposes the internal Long id. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CancellationPolicyResponse {

    private UUID publicId;
    private String name;
    private CancellationPolicyLevel level;
    private UUID ownerPublicId;
    private Integer version;
    private Boolean active;
    private LocalDate effectiveFrom;
    private Boolean gstOnChargeApplicable;
    private Boolean tcsRefundable;
    private List<CancellationPolicyBandDto> bands;

    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}