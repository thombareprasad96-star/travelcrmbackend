package com.crm.travelcrm.platform.audit.service;

import com.crm.travelcrm.platform.audit.dto.PlatformAuditLogResponse;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

/** Read side of the platform audit ledger (the write side is {@code PlatformAuditRecorder}). */
public interface PlatformAuditService {

    Page<PlatformAuditLogResponse> list(PlatformAuditAction action, Boolean success,
                                        LocalDate from, LocalDate to, String q, Pageable pageable);

    /** The action catalogue, for the console filter dropdown. */
    List<String> actions();
}