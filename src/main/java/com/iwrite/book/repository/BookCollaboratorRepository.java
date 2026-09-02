package com.iwrite.book.repository;

import com.iwrite.book.entity.BookCollaborator;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookCollaboratorRepository extends JpaRepository<BookCollaborator, UUID> {

    List<BookCollaborator> findByBook_IdAndTenant_IdOrderByUser_DisplayNameAscUser_IdAsc(UUID bookId, UUID tenantId);

    Optional<BookCollaborator> findByBook_IdAndTenant_IdAndUser_Id(UUID bookId, UUID tenantId, UUID userId);

    boolean existsByBook_IdAndTenant_IdAndUser_Id(UUID bookId, UUID tenantId, UUID userId);
}
