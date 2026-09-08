package com.iwrite.chapter.repository;

import com.iwrite.chapter.entity.Chapter;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChapterRepository extends JpaRepository<Chapter, UUID> {

    List<Chapter> findByBookIdOrderBySortOrderAsc(UUID bookId);

    List<Chapter> findBySectionIdOrderBySortOrderAsc(UUID sectionId);

    @Query("""
            select chapter
            from Chapter chapter
            join chapter.section section
            join section.book book
            where chapter.id = :chapterId
              and book.tenant.id = :tenantId
            """)
    Optional<Chapter> findByIdAndTenantId(
            @Param("chapterId") UUID chapterId,
            @Param("tenantId") UUID tenantId
    );

    /**
     * The Book a Chapter belongs to, for a caller that must take the Book row lock before it writes the
     * Chapter. It resolves the owning Book without loading the Chapter, so the Chapter is read for the
     * first time under the lock instead of carrying pre-lock state into the write.
     */
    @Query("""
            select book.id
            from Chapter chapter
            join chapter.section section
            join section.book book
            where chapter.id = :chapterId
              and book.tenant.id = :tenantId
            """)
    Optional<UUID> findBookIdByIdAndTenantId(
            @Param("chapterId") UUID chapterId,
            @Param("tenantId") UUID tenantId
    );

    /**
     * Locks a single Chapter row of an already proven Book.
     *
     * <p>It matches on the Chapter's own {@code book_id} rather than joining up to the Workspace so the
     * statement locks the Chapter row and nothing else: a {@code for no key update} over the joined
     * Section and Book would take the Book row here too, behind the capability proof that already took
     * it and against the Book-first order every Manuscript surface follows.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select chapter
            from Chapter chapter
            where chapter.id = :chapterId
              and chapter.book.id = :bookId
            """)
    Optional<Chapter> findByIdAndBookIdForUpdate(
            @Param("chapterId") UUID chapterId,
            @Param("bookId") UUID bookId
    );

    int countByBookId(UUID bookId);

    int countBySectionId(UUID sectionId);
}
