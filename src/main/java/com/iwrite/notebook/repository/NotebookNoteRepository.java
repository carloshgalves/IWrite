package com.iwrite.notebook.repository;

import com.iwrite.notebook.entity.NotebookNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface NotebookNoteRepository extends JpaRepository<NotebookNote, UUID> {

    List<NotebookNote> findByBookIdOrderByUpdatedAtDescIdAsc(UUID bookId);

    List<NotebookNote> findByBookIdAndCategoryIdOrderByUpdatedAtDescIdAsc(UUID bookId, UUID categoryId);

    List<NotebookNote> findByCategoryId(UUID categoryId);

    @Query(value = """
            select note.*
            from notebook_notes note
            left join notebook_categories category on category.id = note.category_id
            where note.book_id = :bookId
              and (
                note.title ilike :pattern escape '\\'
                or coalesce(note.content, '') ilike :pattern escape '\\'
              )
            order by
              case
                when note.title ilike :pattern escape '\\' then 0
                else 1
              end,
              note.updated_at desc,
              note.id
            limit :limit
            """, nativeQuery = true)
    List<NotebookNote> searchBookNotes(@Param("bookId") UUID bookId, @Param("pattern") String pattern, @Param("limit") int limit);
}
