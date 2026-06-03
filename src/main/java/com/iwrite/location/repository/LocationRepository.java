package com.iwrite.location.repository;

import com.iwrite.location.entity.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    List<Location> findByBookIdOrderByNameAscIdAsc(UUID bookId);

    @Query(value = """
            select location.*
            from locations location
            where location.book_id = :bookId
              and (
                location.name ilike :pattern escape '\\'
                or coalesce(location.type, '') ilike :pattern escape '\\'
                or coalesce(location.description, '') ilike :pattern escape '\\'
                or coalesce(location.history_context, '') ilike :pattern escape '\\'
                or coalesce(location.narrative_importance, '') ilike :pattern escape '\\'
                or coalesce(location.notes, '') ilike :pattern escape '\\'
              )
            order by
              case
                when location.name ilike :pattern escape '\\' then 0
                when coalesce(location.type, '') ilike :pattern escape '\\' then 1
                else 2
              end,
              location.name,
              location.id
            limit :limit
            """, nativeQuery = true)
    List<Location> searchBookLocations(@Param("bookId") UUID bookId, @Param("pattern") String pattern, @Param("limit") int limit);
}
