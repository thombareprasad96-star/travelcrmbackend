package com.crm.travelcrm.communication.repository;

import com.crm.travelcrm.communication.entity.CommMessageEmail;
import com.crm.travelcrm.communication.enums.EmailFolder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Email-only headers and mailbox placement. One row per {@code comm_messages} row of channel EMAIL. */
public interface CommMessageEmailRepository extends JpaRepository<CommMessageEmail, Long> {

    Optional<CommMessageEmail> findByTenantIdAndMessageIdAndDeletedAtIsNull(Long tenantId, Long messageId);

    /** Batch fetch for a timeline page — one query, not one per row. */
    List<CommMessageEmail> findByTenantIdAndMessageIdInAndDeletedAtIsNull(
            Long tenantId, Collection<Long> messageIds);

    /**
     * Threading lookup: has this {@code Message-ID} already been stored?
     *
     * <p>Identity is the header, never the IMAP UID — UIDs are per-mailbox and reset whenever
     * {@code UIDVALIDITY} changes, so a re-validated mailbox would re-import the entire history.
     */
    Optional<CommMessageEmail> findByTenantIdAndMessageIdHeaderAndDeletedAtIsNull(
            Long tenantId, String messageIdHeader);

    long countByTenantIdAndFolderAndDeletedAtIsNull(Long tenantId, EmailFolder folder);
}
