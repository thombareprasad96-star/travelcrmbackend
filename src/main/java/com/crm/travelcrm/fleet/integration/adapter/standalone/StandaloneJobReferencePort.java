package com.crm.travelcrm.fleet.integration.adapter.standalone;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.fleet.integration.spi.FleetJobReference;
import com.crm.travelcrm.fleet.integration.spi.FleetJobReferencePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Fleet-only deployment: there is no booking module, so a job publicId cannot be resolved.
 *
 * <p><b>This is the fallback, wired with {@code @ConditionalOnMissingBean}.</b> If the CRM adapter
 * is present it wins; if the product-mode property is misconfigured, Spring lands here — which is
 * the safe direction. A misconfiguration that fell through to the CRM adapter would let a
 * supposedly-isolated Fleet deployment read the booking table.
 *
 * <p>A standalone operator still records what a trip was for: they type it (party name, tour name,
 * their own job number), and it is snapshotted verbatim as free text. What is unavailable is
 * <em>looking a job up by id</em>, because there is nothing to look it up in.
 *
 * <p><b>Throws instead of returning empty, deliberately.</b> Returning empty would present as
 * "booking not found", sending the operator hunting for a row that was never possible. The 400 says
 * what is actually true about the deployment.
 */
@Component
@ConditionalOnMissingBean(FleetJobReferencePort.class)
public class StandaloneJobReferencePort implements FleetJobReferencePort {

    @Override
    public Optional<FleetJobReference> resolve(UUID publicId, Long tenantId) {
        throw new BusinessException(
                "Booking links are not available in this deployment — enter the job reference as text instead",
                HttpStatus.BAD_REQUEST);
    }

    @Override
    public boolean supportsLookup() {
        return false;
    }
}
