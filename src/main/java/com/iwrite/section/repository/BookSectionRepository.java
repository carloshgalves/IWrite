package com.iwrite.section.repository;

import com.iwrite.section.entity.BookSection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookSectionRepository extends JpaRepository<BookSection, UUID> {

    List<BookSection> findByBookIdOrderBySortOrderAsc(UUID bookId);

    Optional<BookSection> findByIdAndBook_Tenant_Id(UUID sectionId, UUID tenantId);

    /**
     * The Book a Section belongs to, for a caller that must take the Book row lock before it writes the
     * Section. It resolves the owning Book without loading the Section, so the Section is read for the
     * first time under the lock instead of carrying pre-lock state into the write.
     */
    @Query("""
            select book.id
            from BookSection section
            join section.book book
            where section.id = :sectionId
              and book.tenant.id = :tenantId
            """)
    Optional<UUID> findBookIdByIdAndTenantId(
            @Param("sectionId") UUID sectionId,
            @Param("tenantId") UUID tenantId
    );

    /**
     * Locks a single Section row of an already proven Book.
     *
     * <p>It matches on the Section's own {@code book_id} rather than joining up to the Workspace so the
     * statement locks the Section row and nothing else: a {@code for no key update} over the joined Book
     * would take the Book row here too, behind the capability proof that already took it and against the
     * Book-first order every Manuscript surface follows.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select section
            from BookSection section
            where section.id = :sectionId
              and section.book.id = :bookId
            """)
    Optional<BookSection> findByIdAndBookIdForUpdate(
            @Param("sectionId") UUID sectionId,
            @Param("bookId") UUID bookId
    );

    int countByBookId(UUID bookId);
}
