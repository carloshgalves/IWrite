package com.iwrite.profile;

import com.iwrite.profile.dto.ProfileResponse;
import com.iwrite.profile.dto.ProfileUpdateRequest;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.user.context.CurrentUserProvider;
import com.iwrite.user.entity.User;
import com.iwrite.user.entity.UserPersona;
import com.iwrite.user.entity.UserPersonaType;
import jakarta.persistence.EntityManager;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Exercises the two profile reads against real PostgreSQL with a PATCH committed between them. */
@SuppressWarnings("removal")
@Import(ProfileSnapshotConsistencyIntegrationTest.PauseConfiguration.class)
class ProfileSnapshotConsistencyIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private ProfileService profileService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private PersonaReadProbe personaReadProbe;

    @MockBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentPatchCannotMakeGetReturnAHybridProfile() throws Exception {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        UUID userId = transactions.execute(status -> {
            User user = new User();
            user.setDisplayName("Perfil anterior");
            user.setEmail("snapshot-profile-" + UUID.randomUUID() + "@iwrite.local");
            user.setTimeZoneId("America/Sao_Paulo");
            entityManager.persist(user);

            UserPersona writer = new UserPersona();
            writer.setUserId(user.getId());
            writer.setPersona(UserPersonaType.WRITER);
            writer.setPrimary(true);
            entityManager.persist(writer);
            entityManager.flush();
            return user.getId();
        });
        when(currentUserProvider.userId()).thenReturn(userId);

        personaReadProbe.arm();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ProfileResponse> concurrentGet = executor.submit(profileService::get);
            personaReadProbe.awaitGetBetweenUserAndPersonaReads();

            ProfileUpdateRequest update = new ProfileUpdateRequest(
                    "Perfil posterior",
                    "America/Fortaleza",
                    List.of("WRITER", "EDITOR"),
                    "EDITOR"
            );
            profileService.update(update);
            personaReadProbe.releaseGet();

            ProfileResponse response = concurrentGet.get(10, TimeUnit.SECONDS);
            boolean entirelyBefore = response.displayName().equals("Perfil anterior")
                    && response.timeZone().equals("America/Sao_Paulo")
                    && response.personas().equals(List.of(
                    new ProfileResponse.PersonaResponse("WRITER", true)));
            boolean entirelyAfter = response.displayName().equals("Perfil posterior")
                    && response.timeZone().equals("America/Fortaleza")
                    && response.personas().equals(List.of(
                    new ProfileResponse.PersonaResponse("WRITER", false),
                    new ProfileResponse.PersonaResponse("EDITOR", true)));

            assertThat(entirelyBefore || entirelyAfter)
                    .as("GET must return one PostgreSQL snapshot, never old user fields with new personas")
                    .isTrue();
        } finally {
            personaReadProbe.releaseGet();
            executor.shutdownNow();
        }
    }

    @TestConfiguration
    static class PauseConfiguration {

        @Bean
        PersonaReadProbe personaReadProbe() {
            return new PersonaReadProbe();
        }
    }

    @Aspect
    static class PersonaReadProbe {

        private final AtomicBoolean armed = new AtomicBoolean();
        private volatile CountDownLatch reached = new CountDownLatch(0);
        private volatile CountDownLatch release = new CountDownLatch(0);

        void arm() {
            reached = new CountDownLatch(1);
            release = new CountDownLatch(1);
            armed.set(true);
        }

        void awaitGetBetweenUserAndPersonaReads() throws InterruptedException {
            assertThat(reached.await(10, TimeUnit.SECONDS))
                    .as("GET reached the persona query after reading the user")
                    .isTrue();
        }

        void releaseGet() {
            release.countDown();
        }

        @Around("execution(* com.iwrite.user.repository.UserPersonaRepository.findAllByUserId(..))")
        Object pauseFirstPersonaRead(ProceedingJoinPoint joinPoint) throws Throwable {
            if (armed.compareAndSet(true, false)) {
                reached.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release the profile GET");
                }
            }
            return joinPoint.proceed();
        }
    }
}
