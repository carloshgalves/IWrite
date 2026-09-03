package com.iwrite.writingprogress.service;

import com.iwrite.book.dto.BookRequest;
import com.iwrite.book.service.BookCollaboratorService;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.support.SwitchableCurrentUserProvider;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.user.entity.User;
import com.iwrite.writingprogress.entity.BookWritingSchedule;
import com.iwrite.writingprogress.repository.BookWritingScheduleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_TENANT_ID;
import static com.iwrite.support.SwitchableCurrentUserProvider.DEFAULT_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(WritingScheduleIntegrationTest.CurrentUserTestConfiguration.class)
class WritingScheduleIntegrationTest extends PostgresIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 30);
    private static final Instant DEFAULT_INSTANT = Instant.parse("2026-05-30T12:00:00Z");

    @Autowired
    private BookWritingScheduleRepository scheduleRepository;

    @Autowired
    private SwitchableCurrentUserProvider currentUserProvider;

    @Autowired
    private MutableClock writingScheduleClock;

    @Autowired
    private BookCollaboratorService collaboratorService;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void resetCurrentUserAndClock() {
        currentUserProvider.reset();
        writingScheduleClock.reset();
    }

    @Test
    void newBooksDefaultToEveryDaySchedule() {
        var book = createBook("default schedule");

        var activeSchedule = scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id()).orElseThrow();
        assertThat(activeSchedule.getEffectiveFrom()).isEqualTo(TODAY);
        assertThat(activeSchedule.getEffectiveTo()).isNull();
        assertThat(activeSchedule.getPlannedDays()).containsExactlyInAnyOrder(DayOfWeek.values());
        assertScheduleTimestamps(activeSchedule, DEFAULT_INSTANT);
        assertThat(scheduleRepository.countByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id())).isEqualTo(1);
    }

    @Test
    void creatingABookDoesNotCarryAPersonalRoutine() {
        // The routine is a Personal Book Writing Goal, so it is not part of the shared Book contract:
        // a new Book starts on the default routine and each User changes their own from the goal.
        BookRequest request = new BookRequest("custom schedule", null, null, null, null);

        var book = bookService.create(request);

        var activeSchedule = scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id()).orElseThrow();
        assertThat(activeSchedule.getPlannedDays()).containsExactlyInAnyOrder(DayOfWeek.values());
    }

    @Test
    void emptyScheduleIsRejected() {
        var book = createBook("empty schedule");

        assertThatThrownBy(() -> setPersonalPlannedWritingDays(book.id(), List.of()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("plannedWritingDays");
    }

    @Test
    void updatingScheduleClosesCurrentVersionAndCreatesTomorrowVersion() {
        var book = createBook("change schedule");
        writingScheduleClock.setInstant(DEFAULT_INSTANT);

        var updatedGoal = setPersonalPlannedWritingDays(
                book.id(),
                List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        );

        assertThat(updatedGoal.plannedWritingDays())
                .containsExactly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
        var schedules = scheduleRepository.findByUserIdAndBookIdOverlappingPeriod(DEFAULT_USER_ID, book.id(), TODAY.minusDays(1), TODAY.plusDays(2));
        assertThat(schedules).hasSize(2);
        assertThat(schedules.get(0).getEffectiveFrom()).isEqualTo(TODAY);
        assertThat(schedules.get(0).getEffectiveTo()).isEqualTo(TODAY.plusDays(1));
        assertThat(schedules.get(1).getEffectiveFrom()).isEqualTo(TODAY.plusDays(1));
        assertThat(schedules.get(1).getEffectiveTo()).isNull();
        assertThat(scheduleRepository.countByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id())).isEqualTo(1);
    }

    @Test
    void repeatedFutureScheduleEditReplacesPendingActiveVersion() {
        var book = createBook("replace pending schedule");
        writingScheduleClock.setInstant(DEFAULT_INSTANT);
        setPersonalPlannedWritingDays(
                book.id(),
                List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        );

        writingScheduleClock.setInstant(DEFAULT_INSTANT);
        setPersonalPlannedWritingDays(book.id(), List.of(DayOfWeek.MONDAY));

        var schedules = scheduleRepository.findByUserIdAndBookIdOverlappingPeriod(DEFAULT_USER_ID, book.id(), TODAY.minusDays(1), TODAY.plusDays(2));
        assertThat(schedules).hasSize(2);
        assertThat(schedules.get(1).getEffectiveFrom()).isEqualTo(TODAY.plusDays(1));
        assertThat(schedules.get(1).getPlannedDays()).containsExactly(DayOfWeek.MONDAY);
        assertThat(scheduleRepository.countByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id())).isEqualTo(1);
    }

    @Test
    void unchangedScheduleDoesNotCreateNewVersion() {
        var book = createBook("unchanged schedule");
        writingScheduleClock.setInstant(DEFAULT_INSTANT);
        setPersonalPlannedWritingDays(book.id(), List.of(DayOfWeek.values()));

        var schedules = scheduleRepository.findByUserIdAndBookIdOverlappingPeriod(DEFAULT_USER_ID, book.id(), TODAY.minusDays(1), TODAY.plusDays(2));
        assertThat(schedules).hasSize(1);
        assertThat(scheduleRepository.countByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id())).isEqualTo(1);
    }

    @Test
    void scheduleCreationAndChangeUseCurrentUsersLocalDateNearUtcMidnight() {
        Instant creationInstant = Instant.parse("2026-05-30T00:30:00Z");
        writingScheduleClock.setInstant(creationInstant);
        currentUserProvider.switchTo(DEFAULT_USER_ID, DEFAULT_TENANT_ID, ZoneId.of("America/Los_Angeles"));
        LocalDate localToday = LocalDate.of(2026, 5, 29);

        var book = createBook("timezone schedule");
        assertThat(writingScheduleClock.readCount()).isEqualTo(1);

        Instant changeInstant = Instant.parse("2026-05-30T01:30:00Z");
        writingScheduleClock.setInstant(changeInstant);

        var updatedGoal = setPersonalPlannedWritingDays(book.id(), List.of(DayOfWeek.MONDAY));

        assertThat(writingScheduleClock.readCount()).isEqualTo(1);
        assertThat(updatedGoal.plannedWritingDays()).containsExactly(DayOfWeek.MONDAY);
        var schedules = scheduleRepository.findByUserIdAndBookIdOverlappingPeriod(
                DEFAULT_USER_ID,
                book.id(),
                localToday.minusDays(1),
                localToday.plusDays(2)
        );
        assertThat(schedules).hasSize(2);
        assertThat(schedules.get(0).getEffectiveFrom()).isEqualTo(localToday);
        assertThat(schedules.get(0).getEffectiveTo()).isEqualTo(localToday.plusDays(1));
        assertThat(schedules.get(0).getCreatedAt().toInstant()).isEqualTo(creationInstant);
        assertThat(schedules.get(0).getUpdatedAt().toInstant()).isEqualTo(changeInstant);
        assertThat(schedules.get(1).getEffectiveFrom()).isEqualTo(localToday.plusDays(1));
        assertThat(schedules.get(1).getEffectiveTo()).isNull();
        assertThat(schedules.get(1).getPlannedDays()).containsExactly(DayOfWeek.MONDAY);
        assertScheduleTimestamps(schedules.get(1), changeInstant);
        assertThat(scheduleRepository.countByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id())).isEqualTo(1);
    }

    @Test
    void scheduleTimestampsPersistMicrosecondPrecisionAfterReload() {
        Instant microsecondInstant = Instant.parse("2026-06-24T01:23:45.123456Z");
        writingScheduleClock.setInstant(microsecondInstant);

        var book = createBook("microsecond schedule timestamp");

        var activeSchedule = scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id()).orElseThrow();
        assertScheduleTimestamps(activeSchedule, microsecondInstant);
    }

    @Test
    void timezoneRollbackSupersedesLaterPendingScheduleAtNewLocalTargetDate() {
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        ZoneId losAngeles = ZoneId.of("America/Los_Angeles");
        Instant creationInstant = Instant.parse("2026-05-31T12:00:00Z");
        Instant changeInstant = Instant.parse("2026-06-01T06:30:00Z");
        LocalDate historicalDate = LocalDate.of(2026, 5, 31);
        LocalDate rollbackTargetDate = LocalDate.of(2026, 6, 1);
        LocalDate oldPendingDate = LocalDate.of(2026, 6, 2);

        switchUser(DEFAULT_USER_ID, tokyo);
        writingScheduleClock.setInstant(creationInstant);
        var book = createBook("timezone rollback pending schedule");

        UUID otherUserId = createMember("schedule-rollback-other@iwrite.local");
        collaboratorService.grantInternal(book.id(), otherUserId, DEFAULT_USER_ID);
        switchUser(otherUserId, tokyo);
        writingScheduleClock.setInstant(creationInstant);
        personalBookWritingGoalService.getGoal(book.id());

        switchUser(DEFAULT_USER_ID, tokyo);
        writingScheduleClock.setInstant(changeInstant);
        setPersonalPlannedWritingDays(book.id(), List.of(DayOfWeek.MONDAY));
        assertThat(writingScheduleClock.readCount()).isEqualTo(1);

        switchUser(DEFAULT_USER_ID, losAngeles);
        writingScheduleClock.setInstant(changeInstant);

        var updatedGoal = setPersonalPlannedWritingDays(book.id(), List.of(DayOfWeek.TUESDAY));

        assertThat(writingScheduleClock.readCount()).isEqualTo(1);
        assertThat(updatedGoal.plannedWritingDays()).containsExactly(DayOfWeek.TUESDAY);
        var schedules = scheduleRepository.findByUserIdAndBookIdOverlappingPeriod(
                DEFAULT_USER_ID,
                book.id(),
                historicalDate,
                oldPendingDate.plusDays(2)
        );
        assertThat(schedules).hasSize(2);
        assertThat(schedules.get(0).getEffectiveFrom()).isEqualTo(historicalDate);
        assertThat(schedules.get(0).getEffectiveTo()).isEqualTo(rollbackTargetDate);
        assertThat(schedules.get(0).getPlannedDays()).containsExactlyInAnyOrder(DayOfWeek.values());
        assertThat(schedules.get(1).getEffectiveFrom()).isEqualTo(rollbackTargetDate);
        assertThat(schedules.get(1).getEffectiveTo()).isNull();
        assertThat(schedules.get(1).getPlannedDays()).containsExactly(DayOfWeek.TUESDAY);
        assertScheduleTimestamps(schedules.get(1), changeInstant);
        assertThat(scheduleRepository.countByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id())).isEqualTo(1);

        assertThat(scheduleForDate(DEFAULT_USER_ID, book.id(), historicalDate).getPlannedDays())
                .containsExactlyInAnyOrder(DayOfWeek.values());
        assertThat(scheduleForDate(DEFAULT_USER_ID, book.id(), rollbackTargetDate).getPlannedDays())
                .containsExactly(DayOfWeek.TUESDAY);
        assertThat(scheduleForDate(DEFAULT_USER_ID, book.id(), oldPendingDate).getPlannedDays())
                .containsExactly(DayOfWeek.TUESDAY);

        var otherUserSchedules = scheduleRepository.findByUserIdAndBookIdOverlappingPeriod(
                otherUserId,
                book.id(),
                historicalDate,
                oldPendingDate.plusDays(2)
        );
        assertThat(otherUserSchedules).hasSize(1);
        assertThat(otherUserSchedules.get(0).getEffectiveFrom()).isEqualTo(historicalDate);
        assertThat(otherUserSchedules.get(0).getEffectiveTo()).isNull();
        assertThat(otherUserSchedules.get(0).getPlannedDays()).containsExactlyInAnyOrder(DayOfWeek.values());
    }

    @Test
    void timezoneMoveEastStillSplitsScheduleAtNewLocalTargetDate() {
        ZoneId losAngeles = ZoneId.of("America/Los_Angeles");
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        Instant creationInstant = Instant.parse("2026-05-31T12:00:00Z");
        Instant changeInstant = Instant.parse("2026-06-01T06:30:00Z");
        LocalDate historicalDate = LocalDate.of(2026, 5, 31);
        LocalDate firstTargetDate = LocalDate.of(2026, 6, 1);
        LocalDate eastTargetDate = LocalDate.of(2026, 6, 2);

        switchUser(DEFAULT_USER_ID, losAngeles);
        writingScheduleClock.setInstant(creationInstant);
        var book = createBook("timezone east pending schedule");

        writingScheduleClock.setInstant(changeInstant);
        setPersonalPlannedWritingDays(book.id(), List.of(DayOfWeek.MONDAY));

        switchUser(DEFAULT_USER_ID, tokyo);
        writingScheduleClock.setInstant(changeInstant);
        setPersonalPlannedWritingDays(book.id(), List.of(DayOfWeek.WEDNESDAY));

        var schedules = scheduleRepository.findByUserIdAndBookIdOverlappingPeriod(
                DEFAULT_USER_ID,
                book.id(),
                historicalDate,
                eastTargetDate.plusDays(2)
        );
        assertThat(schedules).hasSize(3);
        assertThat(schedules.get(0).getEffectiveFrom()).isEqualTo(historicalDate);
        assertThat(schedules.get(0).getEffectiveTo()).isEqualTo(firstTargetDate);
        assertThat(schedules.get(0).getPlannedDays()).containsExactlyInAnyOrder(DayOfWeek.values());
        assertThat(schedules.get(1).getEffectiveFrom()).isEqualTo(firstTargetDate);
        assertThat(schedules.get(1).getEffectiveTo()).isEqualTo(eastTargetDate);
        assertThat(schedules.get(1).getPlannedDays()).containsExactly(DayOfWeek.MONDAY);
        assertThat(schedules.get(2).getEffectiveFrom()).isEqualTo(eastTargetDate);
        assertThat(schedules.get(2).getEffectiveTo()).isNull();
        assertThat(schedules.get(2).getPlannedDays()).containsExactly(DayOfWeek.WEDNESDAY);
        assertThat(scheduleRepository.countByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id())).isEqualTo(1);
    }

    @Test
    void initialScheduleUsesOneInstantForTimestampAndUserLocalEffectiveDate() {
        Instant beforeLocalMidnight = Instant.parse("2026-05-30T02:30:00Z");
        Instant afterLocalMidnight = Instant.parse("2026-05-30T03:30:00Z");
        writingScheduleClock.setInstants(beforeLocalMidnight, afterLocalMidnight);
        currentUserProvider.switchTo(DEFAULT_USER_ID, DEFAULT_TENANT_ID, ZoneId.of("America/Sao_Paulo"));

        var book = createBook("single instant initial schedule");

        var activeSchedule = scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id()).orElseThrow();
        assertThat(activeSchedule.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 5, 29));
        assertScheduleTimestamps(activeSchedule, beforeLocalMidnight);
        assertThat(writingScheduleClock.readCount()).isEqualTo(1);
    }

    @Test
    void lazyScheduleCreationUsesOneInstantForTimestampAndUserLocalEffectiveDate() {
        var book = createBook("single instant lazy schedule");
        var initialSchedule = scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id()).orElseThrow();
        scheduleRepository.delete(initialSchedule);
        scheduleRepository.flush();
        writingScheduleClock.resetReadCount();

        Instant beforeLocalMidnight = Instant.parse("2026-05-30T02:30:00Z");
        Instant afterLocalMidnight = Instant.parse("2026-05-30T03:30:00Z");
        writingScheduleClock.setInstants(beforeLocalMidnight, afterLocalMidnight);
        currentUserProvider.switchTo(DEFAULT_USER_ID, DEFAULT_TENANT_ID, ZoneId.of("America/Sao_Paulo"));

        personalBookWritingGoalService.getGoal(book.id());

        var activeSchedule = scheduleRepository.findFirstByUser_IdAndBookIdAndEffectiveToIsNull(DEFAULT_USER_ID, book.id()).orElseThrow();
        assertThat(activeSchedule.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 5, 29));
        assertScheduleTimestamps(activeSchedule, beforeLocalMidnight);
        assertThat(writingScheduleClock.readCount()).isEqualTo(1);
    }

    @Test
    void updatedAtChangesOnLaterScheduleModificationWithoutChangingCreatedAt() {
        var book = createBook("later schedule timestamp");
        Instant creationInstant = DEFAULT_INSTANT;
        Instant updateInstant = Instant.parse("2026-05-31T12:00:00Z");
        writingScheduleClock.setInstant(updateInstant);

        setPersonalPlannedWritingDays(book.id(), List.of(DayOfWeek.MONDAY));

        var schedules = scheduleRepository.findByUserIdAndBookIdOverlappingPeriod(
                DEFAULT_USER_ID,
                book.id(),
                TODAY.minusDays(1),
                TODAY.plusDays(3)
        );
        assertThat(schedules).hasSize(2);
        assertThat(schedules.get(0).getCreatedAt().toInstant()).isEqualTo(creationInstant);
        assertThat(schedules.get(0).getUpdatedAt().toInstant()).isEqualTo(updateInstant);
        assertScheduleTimestamps(schedules.get(1), updateInstant);
    }

    private void assertScheduleTimestamps(BookWritingSchedule schedule, Instant expectedInstant) {
        OffsetDateTime expectedTimestamp = expectedInstant.atOffset(ZoneOffset.UTC);
        assertThat(schedule.getCreatedAt()).isEqualTo(expectedTimestamp);
        assertThat(schedule.getUpdatedAt()).isEqualTo(expectedTimestamp);
        assertThat(schedule.getCreatedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(schedule.getUpdatedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    private BookWritingSchedule scheduleForDate(UUID userId, UUID bookId, LocalDate date) {
        return scheduleRepository.findByUserIdAndBookIdAndDate(userId, bookId, date).orElseThrow();
    }

    private void switchUser(UUID userId, ZoneId zoneId) {
        currentUserProvider.switchTo(userId, DEFAULT_TENANT_ID, zoneId);
        User user = entityManager.find(User.class, userId);
        if (user != null) {
            user.setTimeZoneId(zoneId.getId());
        }
        entityManager.flush();
    }

    private UUID createMember(String email) {
        User user = new User();
        user.setDisplayName(email);
        user.setEmail(email);
        user.setTimeZoneId("UTC");
        entityManager.persist(user);

        TenantMembership membership = new TenantMembership();
        membership.setTenant(entityManager.getReference(Tenant.class, DEFAULT_TENANT_ID));
        membership.setUser(user);
        membership.setRole(TenantMembershipRole.OWNER);
        entityManager.persist(membership);
        entityManager.flush();
        return user.getId();
    }

    @TestConfiguration
    static class MutableWritingScheduleClockConfig {

        @Bean
        @Primary
        MutableClock mutableWritingScheduleClock() {
            return new MutableClock(DEFAULT_INSTANT, ZoneOffset.UTC);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CurrentUserTestConfiguration {

        @Bean
        @Primary
        SwitchableCurrentUserProvider switchableCurrentUserProvider() {
            return new SwitchableCurrentUserProvider();
        }
    }

    static class MutableClock extends Clock {

        private final ZoneId zone;
        private final List<Instant> instants = new ArrayList<>();
        private int readCount;

        MutableClock(Instant instant, ZoneId zone) {
            this.zone = zone;
            setInstant(instant);
        }

        void setInstant(Instant instant) {
            setInstants(instant);
        }

        void setInstants(Instant firstInstant, Instant... additionalInstants) {
            instants.clear();
            instants.add(firstInstant);
            instants.addAll(Arrays.asList(additionalInstants));
            readCount = 0;
        }

        void reset() {
            setInstant(DEFAULT_INSTANT);
        }

        void resetReadCount() {
            readCount = 0;
        }

        int readCount() {
            return readCount;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId newZone) {
                    return MutableClock.this.withZone(newZone);
                }

                @Override
                public Instant instant() {
                    return MutableClock.this.instant();
                }
            };
        }

        @Override
        public Instant instant() {
            if (readCount >= instants.size()) {
                throw new AssertionError("Unexpected clock read #" + (readCount + 1)
                        + "; only " + instants.size() + " instant(s) supplied");
            }
            return instants.get(readCount++);
        }
    }
}
