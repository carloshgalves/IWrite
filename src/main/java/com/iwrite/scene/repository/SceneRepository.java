package com.iwrite.scene.repository;

import com.iwrite.scene.entity.Scene;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SceneRepository extends JpaRepository<Scene, UUID> {

    List<Scene> findByBookIdOrderBySortOrderAsc(UUID bookId);

    @Query("""
            select scene
            from Scene scene
            join fetch scene.chapter chapter
            join fetch chapter.section section
            left join fetch scene.povCharacter
            where scene.book.id = :bookId
            order by section.sortOrder, chapter.sortOrder, scene.sortOrder
            """)
    List<Scene> findOutlineScenesByBookId(@Param("bookId") UUID bookId);

    List<Scene> findByChapterIdOrderBySortOrderAsc(UUID chapterId);

    int countByChapterId(UUID chapterId);

    @Query("select coalesce(sum(scene.wordCount), 0) from Scene scene where scene.book.id = :bookId")
    long sumWordCountByBookId(@Param("bookId") UUID bookId);

    @Query(value = """
            select scene.*
            from scenes scene
            join chapters chapter on chapter.id = scene.chapter_id
            join sections section on section.id = chapter.section_id
            where scene.book_id = :bookId
              and (
                scene.title ilike :pattern escape '\\'
                or coalesce(scene.summary, '') ilike :pattern escape '\\'
                or coalesce(scene.content_text, '') ilike :pattern escape '\\'
              )
            order by
              case
                when scene.title ilike :pattern escape '\\' then 0
                when coalesce(scene.summary, '') ilike :pattern escape '\\' then 1
                else 2
              end,
              section.sort_order,
              chapter.sort_order,
              scene.sort_order,
              scene.id
            limit :limit
            """, nativeQuery = true)
    List<Scene> searchBookScenes(@Param("bookId") UUID bookId, @Param("pattern") String pattern, @Param("limit") int limit);
}
