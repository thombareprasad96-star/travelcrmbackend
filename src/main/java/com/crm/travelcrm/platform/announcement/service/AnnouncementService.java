package com.crm.travelcrm.platform.announcement.service;

import com.crm.travelcrm.platform.announcement.dto.AnnouncementResponse;
import com.crm.travelcrm.platform.announcement.dto.SendAnnouncementRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Platform broadcasts (SuperAdmin → tenants), delivered via the notification module. */
public interface AnnouncementService {

    AnnouncementResponse send(SendAnnouncementRequest request);

    Page<AnnouncementResponse> history(Pageable pageable);
}