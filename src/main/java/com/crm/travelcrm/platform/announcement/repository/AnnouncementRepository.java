package com.crm.travelcrm.platform.announcement.repository;

import com.crm.travelcrm.platform.announcement.entity.Announcement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    Page<Announcement> findAllByDeletedAtIsNullOrderByCreatedAtDesc(Pageable pageable);
}