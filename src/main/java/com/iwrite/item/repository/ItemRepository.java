package com.iwrite.item.repository;

import com.iwrite.item.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ItemRepository extends JpaRepository<Item, UUID> {

    List<Item> findByBookIdOrderByNameAscIdAsc(UUID bookId);

    @Query(value = """
            select item.*
            from items item
            left join characters owner on owner.id = item.current_owner_character_id
            where item.book_id = :bookId
              and (
                item.name ilike :pattern escape '\\'
                or coalesce(item.type, '') ilike :pattern escape '\\'
                or coalesce(item.description, '') ilike :pattern escape '\\'
                or coalesce(item.origin, '') ilike :pattern escape '\\'
                or coalesce(item.narrative_importance, '') ilike :pattern escape '\\'
                or coalesce(item.notes, '') ilike :pattern escape '\\'
                or coalesce(owner.name, '') ilike :pattern escape '\\'
              )
            order by
              case
                when item.name ilike :pattern escape '\\' then 0
                when coalesce(item.type, '') ilike :pattern escape '\\' then 1
                else 2
              end,
              item.name,
              item.id
            limit :limit
            """, nativeQuery = true)
    List<Item> searchBookItems(@Param("bookId") UUID bookId, @Param("pattern") String pattern, @Param("limit") int limit);
}
