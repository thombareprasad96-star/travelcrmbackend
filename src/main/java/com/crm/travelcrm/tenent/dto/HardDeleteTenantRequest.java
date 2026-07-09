package com.crm.travelcrm.tenent.dto;

import lombok.Data;

/**
 * Double-confirmation for an irreversible tenant hard-delete: the caller must echo the tenant's
 * exact {@code organizationCode}. A mismatch is rejected before anything is deleted.
 */
@Data
public class HardDeleteTenantRequest {
    private String organizationCode;
}
