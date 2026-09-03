package com.iwrite.writingprogress.repository;

import com.iwrite.writingprogress.entity.PersonalBookWritingGoal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PersonalBookWritingGoalRepository extends JpaRepository<PersonalBookWritingGoal, UUID> {

    Optional<PersonalBookWritingGoal> findByUser_IdAndBook_Id(UUID userId, UUID bookId);

    /**
     * Serializes concurrent writes of the same User's goal in the same Book, so two tabs saving at
     * once cannot interleave into a target neither of them chose.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select goal
            from PersonalBookWritingGoal goal
            where goal.user.id = :userId
              and goal.book.id = :bookId
            """)
    Optional<PersonalBookWritingGoal> findByUserIdAndBookIdForUpdate(
            @Param("userId") UUID userId,
            @Param("bookId") UUID bookId
    );
}
