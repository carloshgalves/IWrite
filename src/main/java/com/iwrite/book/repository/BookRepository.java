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

    Optional<Book> findByIdAndTenant_Id(UUID bookId, UUID tenantId);

    @Query("""
            select book
            from Book book
            where book.tenant.id = :tenantId
              and (
                    book.owner.id = :userId
                    or exists (
                        select 1
                        from BookCollaborator collaborator
                        where collaborator.book = book
                          and collaborator.tenant.id = :tenantId
                          and collaborator.user.id = :userId
                    )
              )
            order by book.updatedAt desc, book.id asc
            """)
    List<Book> findAllAccessibleByTenantIdAndUserId(
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId
    );

    @Query("""
            select book
            from Book book
            where book.id = :bookId
              and book.tenant.id = :tenantId
              and (
                    book.owner.id = :userId
                    or exists (
                        select 1
                        from BookCollaborator collaborator
                        where collaborator.book = book
                          and collaborator.tenant.id = :tenantId
                          and collaborator.user.id = :userId
                    )
              )
            """)
    Optional<Book> findAccessibleByIdAndTenantIdAndUserId(
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
