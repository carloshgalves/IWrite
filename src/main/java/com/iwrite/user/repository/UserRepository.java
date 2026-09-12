package com.iwrite.user.repository;

import com.iwrite.book.entity.BookRole;
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
    @Query("""
            select distinct user
            from User user
            where exists (
                    select 1
                    from Book book
                    where book.id = :bookId
                      and book.owner = user
                  )
               or exists (
                    select 1
                    from BookCollaborator collaborator
                    where collaborator.book.id = :bookId
                      and collaborator.user = user
                      and collaborator.role <> :readerRole
                  )
               or exists (
                    select 1
                    from DailyWritingProgress progress
                    where progress.book.id = :bookId
                      and progress.user = user
                      and (progress.productiveWordCountChange <> 0
                           or progress.manuscriptAdjustmentWordCount <> 0)
                  )
               or exists (
                    select 1
                    from BookWordCountEvent event
                    where event.book.id = :bookId
                      and event.actorUser = user
                      and (event.productiveWordDelta <> 0
                           or event.manuscriptWordDelta <> 0)
                  )
            order by user.displayName asc, user.id asc
            """)
    List<User> findBookContributorCandidates(
            @Param("bookId") UUID bookId,
            @Param("readerRole") BookRole readerRole
    );
}
