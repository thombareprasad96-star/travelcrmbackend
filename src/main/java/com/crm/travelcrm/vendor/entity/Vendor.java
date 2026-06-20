package com.crm.travelcrm.vendor.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.vendor.enums.VendorPayStatus;
import com.crm.travelcrm.vendor.enums.VendorStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "vendors",
        indexes = {
                @Index(name = "idx_vendor_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_vendor_code",    columnList = "tenant_id,vendor_code"),
                @Index(name = "idx_vendor_status",  columnList = "tenant_id,status"),
                @Index(name = "idx_vendor_type",    columnList = "tenant_id,vendor_type"),
        }
)
// Wide-table split: rarely-queried bank + financial columns live in secondary tables,
// keyed 1:1 on the vendor PK. The Java API is unchanged (getters read through transparently),
// so no service/mapper/DTO/CSV code changes. Primary `vendors` table drops from ~45 to ~34 cols.
@SecondaryTables({
        @SecondaryTable(
                name = "vendor_bank_details",
                pkJoinColumns = @PrimaryKeyJoinColumn(name = "vendor_id", referencedColumnName = "id"),
                foreignKey = @ForeignKey(name = "fk_vendor_bank_vendor")),
        @SecondaryTable(
                name = "vendor_financials",
                pkJoinColumns = @PrimaryKeyJoinColumn(name = "vendor_id", referencedColumnName = "id"),
                foreignKey = @ForeignKey(name = "fk_vendor_financials_vendor"))
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class Vendor extends BaseTenantEntity {

    @Column(name = "vendor_code", nullable = false, length = 20)
    private String vendorCode;

    @Column(name = "vendor_name", nullable = false, length = 200)
    private String vendorName;










    private String vendorType;

    @Column(name = "contact_person", length = 150)
    private String contactPerson;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "alternate_phone", length = 20)
    private String alternatePhone;

    @Column(name = "email", length = 150)
    private String email;

    @Column(name = "whatsapp", length = 20)
    private String whatsapp;

    @Column(name = "contract_type", length = 50)
    private String contractType;

    @Column(name = "payment_terms", length = 50)
    private String paymentTerms;

    @Column(name = "comm_pref", length = 50)
    private String commPref;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private VendorStatus status = VendorStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_status", nullable = false, length = 20)
    @Builder.Default
    private VendorPayStatus payStatus = VendorPayStatus.UNPAID;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "country", length = 100)
    private String country;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "pincode", length = 10)
    private String pincode;

    @Column(name = "coverage_areas", columnDefinition = "TEXT")
    private String coverageAreas;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "vendor_services", joinColumns = @JoinColumn(name = "vendor_id"))
    @Column(name = "service_name", length = 100)
    @Builder.Default
    private List<String> services = new ArrayList<>();

    @Column(name = "service_description", columnDefinition = "TEXT")
    private String serviceDescription;

    @Column(name = "commission_rate", precision = 5, scale = 2)
    private BigDecimal commissionRate;

    @Column(name = "currency", length = 20)
    private String currency;

    @Column(name = "credit_period", length = 20)
    private String creditPeriod;

    @Column(name = "credit_limit", precision = 14, scale = 2, table = "vendor_financials")
    @Builder.Default
    private BigDecimal creditLimit = BigDecimal.ZERO;

    @Column(name = "opening_balance", precision = 14, scale = 2, table = "vendor_financials")
    @Builder.Default
    private BigDecimal openingBalance = BigDecimal.ZERO;

    @Column(name = "total_business", precision = 14, scale = 2, table = "vendor_financials")
    @Builder.Default
    private BigDecimal totalBusiness = BigDecimal.ZERO;

    @Column(name = "total_paid", precision = 14, scale = 2, table = "vendor_financials")
    @Builder.Default
    private BigDecimal totalPaid = BigDecimal.ZERO;

    @Column(name = "bank_name", length = 100, table = "vendor_bank_details")
    private String bankName;

    @Column(name = "account_name", length = 150, table = "vendor_bank_details")
    private String accountName;

    @Column(name = "account_number", length = 50, table = "vendor_bank_details")
    private String accountNumber;

    @Column(name = "ifsc_code", length = 20, table = "vendor_bank_details")
    private String ifscCode;

    @Column(name = "upi_id", length = 100, table = "vendor_bank_details")
    private String upiId;

    @Column(name = "gst_number", length = 20, table = "vendor_bank_details")
    private String gstNumber;

    @Column(name = "pan_number", length = 12, table = "vendor_bank_details")
    private String panNumber;

    @Column(name = "rating")
    private Double rating;

    @Column(name = "rating_count")
    @Builder.Default
    private Integer ratingCount = 0;

    @Column(name = "verified")
    @Builder.Default
    private Boolean verified = false;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "special_conditions", columnDefinition = "TEXT")
    private String specialConditions;

    @Transient
    public BigDecimal getOutstanding() {
        BigDecimal biz  = totalBusiness  != null ? totalBusiness  : BigDecimal.ZERO;
        BigDecimal paid = totalPaid      != null ? totalPaid      : BigDecimal.ZERO;
        return biz.subtract(paid);
    }
}