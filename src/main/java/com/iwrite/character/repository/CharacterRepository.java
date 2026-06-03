package com.iwrite.character.repository;

import com.iwrite.character.entity.Character;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CharacterRepository extends JpaRepository<Character, UUID> {

    List<Character> findByBookIdOrderByNameAscIdAsc(UUID bookId);

    @Query(value = """
            select story_character.*
            from characters story_character
            where story_character.book_id = :bookId
              and (
                story_character.name ilike :pattern escape '\\'
                or coalesce(story_character.nickname, '') ilike :pattern escape '\\'
                or coalesce(story_character.biography, '') ilike :pattern escape '\\'
                or coalesce(story_character.notes, '') ilike :pattern escape '\\'
                or coalesce(story_character.narrative_function, '') ilike :pattern escape '\\'
                or coalesce(story_character.goal, '') ilike :pattern escape '\\'
                or coalesce(story_character.conflict, '') ilike :pattern escape '\\'
                or coalesce(story_character.arc, '') ilike :pattern escape '\\'
                or coalesce(story_character.physical_description, '') ilike :pattern escape '\\'
                or coalesce(story_character.personality, '') ilike :pattern escape '\\'
              )
            order by
              case
                when story_character.name ilike :pattern escape '\\' then 0
                when coalesce(story_character.nickname, '') ilike :pattern escape '\\' then 1
                else 2
              end,
              story_character.name,
              story_character.id
            limit :limit
            """, nativeQuery = true)
    List<Character> searchBookCharacters(@Param("bookId") UUID bookId, @Param("pattern") String pattern, @Param("limit") int limit);
}
