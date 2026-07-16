package com.crm.travelcrm.subagent.dto;

import com.crm.travelcrm.subagent.license.dto.SubAgentLicenseRequestResponse;
import lombok.Builder;
import lombok.Getter;

/**
 * Result of provisioning a sub-agent. Two outcomes:
 *
 * <ul>
 *   <li>A licensed seat was free → {@code licenseRequired=false}, {@code licenseRequest=null}, and the
 *       sub-agent is {@code ACTIVE} immediately (the classic path).</li>
 *   <li>The tenant was over its seat cap → {@code licenseRequired=true}: the sub-agent is created
 *       {@code PENDING_LICENSE} (login disabled) and a seat-license purchase ({@code licenseRequest})
 *       is opened. The frontend sends the tenant to pay {@code licenseRequest.invoicePublicId}; a
 *       SuperAdmin then verifies payment and approves to activate the sub-agent.</li>
 * </ul>
 */
@Getter
@Builder
public class CreateSubAgentResponse {

    private SubAgentResponse subAgent;

    /** True when the tenant was over cap and must purchase a seat before this sub-agent can activate. */
    private boolean licenseRequired;

    /** The opened seat-license purchase (with the invoice to pay). Null when a seat was free. */
    private SubAgentLicenseRequestResponse licenseRequest;
}