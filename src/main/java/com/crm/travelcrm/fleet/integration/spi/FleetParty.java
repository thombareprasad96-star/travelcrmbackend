package com.crm.travelcrm.fleet.integration.spi;

import java.util.UUID;

/**
 * A counterparty a fleet asset belongs to or is hired from — the owner of an attached/market
 * vehicle, or a garage. In CRM mode this is a {@code Vendor}; in standalone it is a fleet-owned
 * party record.
 *
 * <p>Only the three fields {@code FleetVehicle} actually snapshots. The CRM {@code Vendor} maps
 * across three tables via {@code @SecondaryTable}, so loading the whole entity to read one name is
 * also a performance win, not just a decoupling one.
 */
public record FleetParty(Long id, UUID publicId, String name) {
}
