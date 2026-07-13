package com.crm.travelcrm.booking.cancellation.repository;

import com.crm.travelcrm.booking.cancellation.entity.BookingDocument;
import com.crm.travelcrm.booking.cancellation.enums.DocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

@Repository
public interface BookingDocumentRepository extends JpaRepository<BookingDocument, Long> {

    /** The cancellation note (credit OR debit) for a booking. */
    Optional<BookingDocument> findFirstByBookingIdAndDocTypeInAndDeletedAtIsNullOrderByIdDesc(
            Long bookingId, Collection<DocumentType> docTypes);

    /** A specific document type for a booking (e.g. the refund voucher). */
    Optional<BookingDocument> findFirstByBookingIdAndDocTypeAndDeletedAtIsNullOrderByIdDesc(
            Long bookingId, DocumentType docType);

    boolean existsByBookingIdAndDocTypeInAndDeletedAtIsNull(Long bookingId, Collection<DocumentType> docTypes);
}