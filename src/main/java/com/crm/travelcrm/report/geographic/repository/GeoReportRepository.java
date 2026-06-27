package com.crm.travelcrm.report.geographic.repository;

import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Report-owned, read-only aggregation over {@code Lead} for the Geographic Distribution report.
 * Kept separate from {@code LeadRepository} so the lead module is untouched. Each grouped row is
 * {@code [label, country, total, fresh, converted]}; "fresh" = Fresh-Lead type, "converted" =
 * Converted stage (the only temperature/outcome signals the Lead model carries).
 */
public interface GeoReportRepository extends Repository<Lead, Long> {

    @Query("""
            SELECT l.departCity, l.departCountry, COUNT(l),
                   SUM(CASE WHEN l.leadType  = com.crm.travelcrm.lead.enums.LeadType.FRESH_LEAD THEN 1L ELSE 0L END),
                   SUM(CASE WHEN l.leadStage = com.crm.travelcrm.lead.enums.LeadStage.CONVERTED THEN 1L ELSE 0L END)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.createdAt BETWEEN :from AND :to
              AND (:leadType  IS NULL OR l.leadType  = :leadType)
              AND (:leadStage IS NULL OR l.leadStage = :leadStage)
              AND (:search    IS NULL OR LOWER(COALESCE(l.departCity, '')) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
            GROUP BY l.departCity, l.departCountry
            ORDER BY COUNT(l) DESC
            """)
    List<Object[]> aggregateByCity(
            @Param("tenantId")  Long tenantId,
            @Param("from")      LocalDateTime from,
            @Param("to")        LocalDateTime to,
            @Param("leadType")  LeadType leadType,
            @Param("leadStage") LeadStage leadStage,
            @Param("search")    String search);

    @Query("""
            SELECT l.departCountry, l.departCountry, COUNT(l),
                   SUM(CASE WHEN l.leadType  = com.crm.travelcrm.lead.enums.LeadType.FRESH_LEAD THEN 1L ELSE 0L END),
                   SUM(CASE WHEN l.leadStage = com.crm.travelcrm.lead.enums.LeadStage.CONVERTED THEN 1L ELSE 0L END)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.createdAt BETWEEN :from AND :to
              AND (:leadType  IS NULL OR l.leadType  = :leadType)
              AND (:leadStage IS NULL OR l.leadStage = :leadStage)
              AND (:search    IS NULL OR LOWER(COALESCE(l.departCountry, '')) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
            GROUP BY l.departCountry
            ORDER BY COUNT(l) DESC
            """)
    List<Object[]> aggregateByCountry(
            @Param("tenantId")  Long tenantId,
            @Param("from")      LocalDateTime from,
            @Param("to")        LocalDateTime to,
            @Param("leadType")  LeadType leadType,
            @Param("leadStage") LeadStage leadStage,
            @Param("search")    String search);

    @Query("""
            SELECT COUNT(l),
                   SUM(CASE WHEN l.leadType  = com.crm.travelcrm.lead.enums.LeadType.FRESH_LEAD THEN 1L ELSE 0L END),
                   SUM(CASE WHEN l.leadStage = com.crm.travelcrm.lead.enums.LeadStage.CONVERTED THEN 1L ELSE 0L END)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.createdAt BETWEEN :from AND :to
              AND (:leadType  IS NULL OR l.leadType  = :leadType)
              AND (:leadStage IS NULL OR l.leadStage = :leadStage)
            """)
    List<Object[]> summary(
            @Param("tenantId")  Long tenantId,
            @Param("from")      LocalDateTime from,
            @Param("to")        LocalDateTime to,
            @Param("leadType")  LeadType leadType,
            @Param("leadStage") LeadStage leadStage);
}