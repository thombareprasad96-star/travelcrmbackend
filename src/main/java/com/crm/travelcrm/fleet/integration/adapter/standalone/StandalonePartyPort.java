package com.crm.travelcrm.fleet.integration.adapter.standalone;

import com.crm.travelcrm.fleet.entity.FleetCounterparty;
import com.crm.travelcrm.fleet.integration.spi.FleetParty;
import com.crm.travelcrm.fleet.integration.spi.FleetPartyPort;
import com.crm.travelcrm.fleet.repository.FleetCounterpartyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fleet-only deployment: the party directory is fleet's own {@code fleet_parties} table.
 *
 * <p>Same {@code @ConditionalOnMissingBean} fallback rule as {@link StandaloneJobReferencePort} —
 * a misconfiguration degrades to "reads the fleet directory", never to "reads the vendor table".
 * In CRM mode {@code CrmVendorPartyAdapter} wins the binding and this class is never instantiated,
 * so the two directories never coexist and never have to be reconciled.
 *
 * <p><b>This used to throw.</b> The earlier version had no directory at all and told a standalone
 * operator to type the owner as free text, which was a real hole in the product: attached and market
 * vehicles are the majority of most Indian fleets, not the exception, and an owner's monthly
 * per-party payout statement cannot be grouped by free text.
 *
 * <p>Ownership is enforced here rather than trusted from the caller: {@code resolve} is tenant-
 * scoped, so a party id belonging to another tenant reads as "not found" and never as data. The
 * caller ({@code FleetVehicleServiceImpl}) turns that empty into a 404, which keeps the fail-closed
 * rule the port's contract asks for — a supplied id is never silently dropped.
 */
@Component
@ConditionalOnMissingBean(FleetPartyPort.class)
@RequiredArgsConstructor
public class StandalonePartyPort implements FleetPartyPort {

    private final FleetCounterpartyRepository repository;

    @Override
    public Optional<FleetParty> resolve(UUID publicId, Long tenantId) {
        return repository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .map(StandalonePartyPort::snapshot);
    }

    @Override
    public List<FleetParty> options(Long tenantId) {
        // Active only: a retired owner stays on every vehicle and bill he ever had, but leaves the
        // dropdown, so nobody attaches tomorrow's vehicle to a party they stopped working with.
        return repository.findByTenantIdAndActiveIsTrueAndDeletedAtIsNullOrderByNameAsc(tenantId)
                .stream().map(StandalonePartyPort::snapshot).toList();
    }

    @Override
    public boolean supportsDirectory() {
        return true;
    }

    private static FleetParty snapshot(FleetCounterparty p) {
        return new FleetParty(p.getId(), p.getPublicId(), p.getName());
    }
}
