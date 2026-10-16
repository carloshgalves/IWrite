package com.iwrite.user.repository;

import com.iwrite.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    /**
     * Users whose relationship to this Book is currently authoritative (Owner/collaborator) or is
     * still provable from attributable writing history. A same-Workspace User is deliberately not
     * enough: {@code contributorId} is an untrusted filter, not an identity assertion.
     */
    @Query(value = """
            with candidate_ids as materialized (
                select book.owner_user_id as user_id
                from books book
                where book.id = :bookId

                union

                select collaborator.user_id
                from book_collaborators collaborator
                where collaborator.book_id = :bookId
                  and collaborator.role <> :readerRole

                union

                select progress.user_id
                from book_daily_writing_progress progress
                where progress.book_id = :bookId
                  and (progress.productive_word_count_change <> 0
                       or progress.manuscript_adjustment_word_count <> 0)

                union

                select event.actor_user_id
                from book_word_count_events event
                where event.book_id = :bookId
                  and (event.productive_word_delta <> 0
                       or event.manuscript_word_delta <> 0)
            )
            select candidate.id,
                   candidate.display_name,
                   candidate.email,
                   candidate.time_zone_id,
                   candidate.created_at,
                   candidate.updated_at
            from candidate_ids
            join users candidate on candidate.id = candidate_ids.user_id
            order by candidate.display_name asc, candidate.id asc
            """, nativeQuery = true)
    List<User> findBookContributorCandidates(
            @Param("bookId") UUID bookId,
            @Param("readerRole") String readerRole
    );
}
