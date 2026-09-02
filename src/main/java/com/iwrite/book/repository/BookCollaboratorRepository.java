package com.iwrite.book.repository;

import com.iwrite.book.entity.BookCollaborator;
import com.iwrite.book.entity.BookRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookCollaboratorRepository extends JpaRepository<BookCollaborator, UUID> {

    List<BookCollaborator> findByBook_IdAndTenant_IdOrderByUser_DisplayNameAscUser_IdAsc(UUID bookId, UUID tenantId);

    Optional<BookCollaborator> findByBook_IdAndTenant_IdAndUser_Id(UUID bookId, UUID tenantId, UUID userId);

    boolean existsByBook_IdAndTenant_IdAndUser_Id(UUID bookId, UUID tenantId, UUID userId);

    @Query("""
            select collaborator.role
            from BookCollaborator collaborator
            where collaborator.book.id = :bookId
              and collaborator.tenant.id = :tenantId
              and collaborator.user.id = :userId
            """)
    Optional<BookRole> findRole(
            @Param("bookId") UUID bookId,
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId
    );

    /**
     * Roles of every Book the User collaborates on in the active Workspace, so a Book listing can
     * project effective access without one lookup per Book.
     */
    @Query("""
            select new com.iwrite.book.repository.BookRoleAssignment(collaborator.book.id, collaborator.role)
            from BookCollaborator collaborator
            where collaborator.tenant.id = :tenantId
              and collaborator.user.id = :userId
            """)
    List<BookRoleAssignment> findRolesByTenantIdAndUserId(
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId
    );
}
