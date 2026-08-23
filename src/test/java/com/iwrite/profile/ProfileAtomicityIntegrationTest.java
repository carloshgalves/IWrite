package com.iwrite.profile;

import com.iwrite.profile.dto.ProfileUpdateRequest;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.user.entity.User;
import com.iwrite.user.entity.UserPersona;
import com.iwrite.user.entity.UserPersonaType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies rollback after validation has passed and PostgreSQL fails during persona persistence. */
class ProfileAtomicityIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private ProfileService profileService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void persistenceFailureRollsBackUserAndPersonaChangesTogether() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        UUID userId = transactions.execute(status -> {
            User user = new User();
            user.setDisplayName("Perfil original");
            user.setEmail("atomic-profile-" + UUID.randomUUID() + "@iwrite.local");
            user.setTimeZoneId("America/Sao_Paulo");
            entityManager.persist(user);

            UserPersona writer = new UserPersona();
            writer.setUserId(user.getId());
            writer.setPersona(UserPersonaType.WRITER);
            writer.setPrimary(true);
            entityManager.persist(writer);

            UserPersona editor = new UserPersona();
            editor.setUserId(user.getId());
            editor.setPersona(UserPersonaType.EDITOR);
            editor.setPrimary(false);
            entityManager.persist(editor);
            entityManager.flush();
            return user.getId();
        });

        jdbcTemplate.execute("""
                create function reject_editor_primary_for_profile_atomicity() returns trigger
                language plpgsql as $$
                begin
                    if new.persona = 'EDITOR' and new.is_primary then
                        raise exception 'forced profile persistence failure';
                    end if;
                    return new;
                end;
                $$
                """);
        jdbcTemplate.execute("""
                create trigger reject_editor_primary_for_profile_atomicity
                before insert or update on user_personas
                for each row execute function reject_editor_primary_for_profile_atomicity()
                """);

        try {
            ProfileUpdateRequest request = new ProfileUpdateRequest(
                    "Perfil que deve voltar atrás",
                    "America/Fortaleza",
                    List.of("WRITER", "EDITOR"),
                    "EDITOR"
            );

            assertThatThrownBy(() -> profileService.update(userId, request))
                    .isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("forced profile persistence failure");

            transactions.executeWithoutResult(status -> {
                entityManager.clear();
                User unchanged = entityManager.find(User.class, userId);
                assertThat(unchanged.getDisplayName()).isEqualTo("Perfil original");
                assertThat(unchanged.getTimeZoneId()).isEqualTo("America/Sao_Paulo");

                List<UserPersona> personas = entityManager.createQuery(
                                "select persona from UserPersona persona where persona.userId = :userId",
                                UserPersona.class)
                        .setParameter("userId", userId)
                        .getResultList();
                assertThat(personas).filteredOn(UserPersona::isPrimary).singleElement()
                        .extracting(UserPersona::getPersona).isEqualTo(UserPersonaType.WRITER);
            });
        } finally {
            jdbcTemplate.execute("drop trigger if exists reject_editor_primary_for_profile_atomicity on user_personas");
            jdbcTemplate.execute("drop function if exists reject_editor_primary_for_profile_atomicity()");
        }
    }
}
