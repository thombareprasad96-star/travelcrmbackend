package com.crm.travelcrm.platform.announcement.controller;

import com.crm.travelcrm.auth.mfa.SuperAdminStepUpService;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.util.ClientIp;
import com.crm.travelcrm.platform.announcement.dto.AnnouncementResponse;
import com.crm.travelcrm.platform.announcement.dto.SendAnnouncementRequest;
import com.crm.travelcrm.platform.announcement.service.AnnouncementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Platform announcements (broadcast). SuperAdmin only. */
@RestController
@RequestMapping("/api/super-admin/announcements")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;
    private final SuperAdminStepUpService superAdminStepUpService;

    @PostMapping
    public ResponseEntity<ApiResponse<AnnouncementResponse>> send(
            @Valid @RequestBody SendAnnouncementRequest request,
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest httpRequest) {
        superAdminStepUpService.requireCode(
                superAdmin, mfaCode, "send platform announcement",
                ClientIp.resolve(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.ok(ApiResponse.success("Announcement sent", announcementService.send(request)));
    }

    @GetMapping
    public ResponseEntity<PagedApiResponse<AnnouncementResponse>> history(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<AnnouncementResponse> result = announcementService.history(pageable);
        return ResponseEntity.ok(PagedApiResponse.of(
                "Announcements fetched", result.getContent(), PaginationMeta.from(result)));
    }
}
