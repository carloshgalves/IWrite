package com.iwrite.book.repository;

import com.iwrite.book.entity.Book;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookRepository extends JpaRepository<Book, UUID> {

    List<Book> findAllByTenant_Id(UUID tenantId);

    /**
     * Every accessible Book of the active Workspace with the Book Role behind it, in one statement.
     * Reading the Books and the roles separately would let a collaboration committed between the two
     * statements produce a Book whose role is missing, and the listing would fail as a whole.
     */
    @Query("""
            select new com.iwrite.book.repository.AccessibleBookWithRole(book, collaborator.role)
            from Book book
            left join BookCollaborator collaborator
                on collaborator.book = book
               and collaborator.tenant.id = :tenantId
               and collaborator.user.id = :userId
            where book.tenant.id = :tenantId
              and (book.owner.id = :userId or collaborator.id is not null)
            order by book.updatedAt desc, book.id asc
            """)
    List<AccessibleBookWithRole> findAllAccessibleWithRoleByTenantIdAndUserId(
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId
    );

    /**
     * One accessible Book with the Book Role behind that access, in a single statement, or empty when
     * the Book is unknown to the Workspace or the User has no relationship with it.
     *
     * <p>Both denials leave through the same query, so a Workspace member holding a candidate
     * identifier cannot tell "does not exist" from "exists but is not mine" by query shape or latency.
     * Reading the Book first and the role afterwards would leak exactly that distinction.
     */
    @Query("""
            select new com.iwrite.book.repository.AccessibleBookWithRole(book, collaborator.role)
            from Book book
            left join BookCollaborator collaborator
                on collaborator.book = book
               and collaborator.tenant.id = :tenantId
               and collaborator.user.id = :userId
            where book.id = :bookId
              and book.tenant.id = :tenantId
              and (book.owner.id = :userId or collaborator.id is not null)
            """)
    Optional<AccessibleBookWithRole> findAccessibleWithRoleByIdAndTenantIdAndUserId(
            @Param("bookId") UUID bookId,
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select book
            from Book book
            where book.id = :bookId
              and book.tenant.id = :tenantId
              and book.owner.id = :userId
            """)
    Optional<Book> findOwnedByIdAndTenantIdAndUserIdForUpdate(
            @Param("bookId") UUID bookId,
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select book
            from Book book
            where book.id = :bookId
              and book.tenant.id = :tenantId
            """)
    Optional<Book> findByIdAndTenantIdForUpdate(
            @Param("bookId") UUID bookId,
            @Param("tenantId") UUID tenantId
    );

}
