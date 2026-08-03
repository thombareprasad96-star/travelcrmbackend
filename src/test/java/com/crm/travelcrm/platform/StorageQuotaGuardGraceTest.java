package com.crm.travelcrm.platform;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.fleet.repository.FleetAttachmentRepository;
import com.crm.travelcrm.platform.usage.storage.StorageQuotaGuard;
import com.crm.travelcrm.platform.usage.storage.TenantStorageAssetRepository;
import com.crm.travelcrm.portal.document.repository.TravelerDocumentRepository;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Pins the storage gate's arithmetic — the three-source sum and the compliance grace factor.
 *
 * <p>Two behaviours here carry money/compliance weight and would fail silently if they drifted:
 * fleet attachment bytes must be IN the sum (an unmetered table is invisible storage), and the
 * grace overload must widen the limit for a compliance certificate without waiving it entirely.
 */
@ExtendWith(MockitoExtension.class)
class StorageQuotaGuardGraceTest {

    private static final long MB = 1024L * 1024L;
    private static final Long TENANT = 7L;

    @Mock private TenantRepository tenantRepository;
    @Mock private TenantStorageAssetRepository storageAssetRepository;
    @Mock private TravelerDocumentRepository travelerDocumentRepository;
    @Mock private FleetAttachmentRepository fleetAttachmentRepository;

    private StorageQuotaGuard guard;

    @BeforeEach
    void setUp() {
        guard = new StorageQuotaGuard(
                tenantRepository, storageAssetRepository, travelerDocumentRepository,
                fleetAttachmentRepository);
        Tenant tenant = new Tenant();
        tenant.setMaxStorageMb(100);
        lenient().when(tenantRepository.findById(TENANT)).thenReturn(Optional.of(tenant));
    }

    private void used(long cloudinary, long traveler, long fleet) {
        lenient().when(storageAssetRepository.sumBytesByTenant(TENANT)).thenReturn(cloudinary);
        lenient().when(travelerDocumentRepository.sumBytesByTenant(TENANT)).thenReturn(traveler);
        lenient().when(fleetAttachmentRepository.sumBytesByTenant(TENANT)).thenReturn(fleet);
    }

    @Test
    @DisplayName("fleet attachment bytes count toward the cap — an unmetered table is invisible storage")
    void fleetBytesAreInTheSum() {
        // 40 + 30 + 30 = 100 MB used of 100: even one more byte must be refused.
        used(40 * MB, 30 * MB, 30 * MB);
        assertThrows(BusinessException.class, () -> guard.enforceWithinQuota(TENANT, 1));
        // Remove the fleet term and the same upload passes — that is exactly the hole PART 11 closes.
        used(40 * MB, 30 * MB, 0);
        assertDoesNotThrow(() -> guard.enforceWithinQuota(TENANT, 1));
    }

    @Test
    @DisplayName("grace 1.10 admits a compliance scan between 100% and 110%, refuses beyond")
    void graceWidensButDoesNotWaive() {
        used(100 * MB, 0, 0);   // exactly at the plain limit
        // The plain gate refuses…
        assertThrows(BusinessException.class, () -> guard.enforceWithinQuota(TENANT, 5 * MB));
        // …the compliance gate lets the certificate in (105 ≤ 110)…
        assertDoesNotThrow(() -> guard.enforceWithinQuota(TENANT, 5 * MB, 1.10));
        // …but gross overage is still gross overage (100 + 11 > 110).
        assertThrows(BusinessException.class, () -> guard.enforceWithinQuota(TENANT, 11 * MB, 1.10));
    }

    @Test
    @DisplayName("a grace factor below 1 clamps to the plain limit — grace can only widen, never tighten")
    void graceNeverTightens() {
        used(50 * MB, 0, 0);
        // 50 used + 40 in = 90 ≤ 100. A buggy 0.5 factor would cut the limit to 50 and refuse.
        assertDoesNotThrow(() -> guard.enforceWithinQuota(TENANT, 40 * MB, 0.5));
    }

    @Test
    @DisplayName("no tenant or unlimited plan stays a no-op through the grace overload too")
    void noopPathsHold() {
        assertDoesNotThrow(() -> guard.enforceWithinQuota(null, Long.MAX_VALUE, 1.10));

        Tenant unlimited = new Tenant();
        unlimited.setMaxStorageMb(0);
        when(tenantRepository.findById(9L)).thenReturn(Optional.of(unlimited));
        assertDoesNotThrow(() -> guard.enforceWithinQuota(9L, Long.MAX_VALUE, 1.10));

        when(tenantRepository.findById(404L)).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> guard.enforceWithinQuota(404L, 1, 1.10));
    }
}
