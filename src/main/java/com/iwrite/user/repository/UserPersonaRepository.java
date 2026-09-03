package com.iwrite.user.repository;

import com.iwrite.user.entity.UserPersona;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserPersonaRepository extends JpaRepository<UserPersona, UUID> {

    List<UserPersona> findAllByUserId(UUID userId);

    @Modifying
    @Query("update UserPersona persona set persona.isPrimary = false where persona.userId = :userId and persona.isPrimary = true")
    int clearPrimary(@Param("userId") UUID userId);
}
