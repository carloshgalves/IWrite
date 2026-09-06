package com.iwrite.writingprogress.ledger.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookService;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.scene.entity.Scene;
import com.iwrite.user.context.CurrentUserMembershipService;
import com.iwrite.writingprogress.ledger.entity.BookWordCountEvent;
import com.iwrite.writingprogress.ledger.repository.BookWordCountEventRepository;
import com.iwrite.writingprogress.repository.DailyWritingProgressRepository;
import com.iwrite.writingprogress.service.PersonalBookWritingGoalService;
import com.iwrite.writingprogress.service.WritingDayResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

@Service
public class WordCountEventService {

    private final BookService bookService;
    private final BookWordCountEventRepository eventRepository;
    private final DailyWritingProgressRepository progressRepository;
    private final CurrentUserMembershipService currentUserMembershipService;
    private final PersonalBookWritingGoalService personalBookWritingGoalService;
    private final WritingDayResolver writingDayResolver;
    private final Clock clock;

    public WordCountEventService(
            BookService bookService,
            BookWordCountEventRepository eventRepository,
            DailyWritingProgressRepository progressRepository,
            CurrentUserMembershipService currentUserMembershipService,
            PersonalBookWritingGoalService personalBookWritingGoalService,
            WritingDayResolver writingDayResolver,
            Clock clock
    ) {
        this.bookService = bookService;
        this.eventRepository = eventRepository;
        this.progressRepository = progressRepository;
        this.currentUserMembershipService = currentUserMembershipService;
        this.personalBookWritingGoalService = personalBookWritingGoalService;
        this.writingDayResolver = writingDayResolver;
        this.clock = clock;
    }

    @Transactional
    public WordCountEventRecordResult record(WordCountEventCommand command) {
        validate(command);

        Book book = bookService.getBookForWordCountUpdate(command.bookId());
        return recordForLockedBook(book, command);
    }

    public WordCountEventRecordResult recordForLockedBook(Book book, WordCountEventCommand command) {
        validate(command);
        if (!book.getId().equals(command.bookId())) {
            throw new BadRequestException("bookId must match the locked book");
        }

        UUID actorUserId = currentUserMembershipService.requireCurrentUserMemberId();
        Instant eventInstant = clock.instant();
        OffsetDateTime eventTime = eventInstant.atOffset(ZoneOffset.UTC);
        LocalDate progressDate = writingDayResolver.writingDateFor(eventInstant);
        int inserted = eventRepository.insertIfAbsent(
                UUID.randomUUID(),
                command.bookId(),
                command.sceneId(),
                actorUserId,
                command.originalSceneId(),
                command.sceneTitleSnapshot(),
                command.eventType().name(),
                command.productiveWordDelta(),
                command.manuscriptWordDelta(),
                command.operationId(),
                command.idempotencyKey(),
                command.contentRevisionBefore(),
                command.contentRevisionAfter(),
                command.requestFingerprint(),
                eventTime
        );

        if (inserted == 0) {
            BookWordCountEvent existing = eventRepository
                    .findByBookIdAndIdempotencyKey(command.bookId(), command.idempotencyKey())
                    .orElseThrow(() -> new WordCountEventConflictException("Word-count event idempotency state is inconsistent."));
            if (matches(existing, command, actorUserId)) {
                return WordCountEventRecordResult.ALREADY_RECORDED;
            }
            throw new WordCountEventConflictException("Idempotency key was already used for a different word-count event.");
        }

        if (shouldUpdateDailyRollup(command)) {
            progressRepository.upsertWordCountEventRollup(
                    UUID.randomUUID(),
                    actorUserId,
                    command.bookId(),
                    progressDate,
                    // The daily rollup snapshots the actor's own target for that date, so each
                    // collaborator's history keeps the goal they had chosen and never another's.
                    personalBookWritingGoalService.dailyTargetWordCountFor(command.bookId(), actorUserId),
                    command.knownManuscriptTotalAfterOperation(),
                    command.productiveWordDelta(),
                    command.manuscriptWordDelta(),
                    eventTime
            );
        }
        return WordCountEventRecordResult.RECORDED;
    }

    private void validate(WordCountEventCommand command) {
        if (command == null) {
            throw new BadRequestException("word-count event command is required");
        }
        if (command.bookId() == null) {
            throw new BadRequestException("bookId is required");
        }
        if (command.eventType() == null) {
            throw new BadRequestException("eventType is required");
        }
        if (command.idempotencyKey() == null) {
            throw new BadRequestException("idempotencyKey is required");
        }
        if (command.requestFingerprint() == null || command.requestFingerprint().isBlank()) {
            throw new BadRequestException("requestFingerprint is required");
        }
    }

    private boolean matches(BookWordCountEvent existing, WordCountEventCommand command, UUID actorUserId) {
        if (existing.getRequestFingerprint() == null) {
            return false;
        }

        return Objects.equals(existing.getRequestFingerprint(), command.requestFingerprint())
                && Objects.equals(existing.getBook().getId(), command.bookId())
                && Objects.equals(existing.getActorUser().getId(), actorUserId)
                && Objects.equals(sceneId(existing), command.sceneId())
                && Objects.equals(existing.getOriginalSceneId(), command.originalSceneId())
                && Objects.equals(existing.getSceneTitleSnapshot(), command.sceneTitleSnapshot())
                && existing.getEventType() == command.eventType()
                && Objects.equals(existing.getProductiveWordDelta(), command.productiveWordDelta())
                && Objects.equals(existing.getManuscriptWordDelta(), command.manuscriptWordDelta())
                && Objects.equals(existing.getOperationId(), command.operationId())
                && Objects.equals(existing.getContentRevisionBefore(), command.contentRevisionBefore())
                && Objects.equals(existing.getContentRevisionAfter(), command.contentRevisionAfter());
    }

    private boolean shouldUpdateDailyRollup(WordCountEventCommand command) {
        return command.productiveWordDelta() != 0 || command.manuscriptWordDelta() != 0;
    }

    private UUID sceneId(BookWordCountEvent event) {
        Scene scene = event.getScene();
        return scene == null ? null : scene.getId();
    }
}
