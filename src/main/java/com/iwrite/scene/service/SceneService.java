package com.iwrite.scene.service;

import com.iwrite.book.authorization.BookCapability;
import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookAccessService;
import com.iwrite.chapter.entity.Chapter;
import com.iwrite.chapter.service.ChapterService;
import com.iwrite.character.entity.Character;
import com.iwrite.character.service.CharacterService;
import com.iwrite.common.dto.ReorderRequest;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ConflictException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.common.validation.RequestValidation;
import com.iwrite.common.wordcount.WordCountService;
import com.iwrite.item.entity.Item;
import com.iwrite.item.service.ItemService;
import com.iwrite.location.entity.Location;
import com.iwrite.location.service.LocationService;
import com.iwrite.observability.BusinessTelemetry;
import com.iwrite.scene.authorization.SceneContentAuthority;
import com.iwrite.scene.dto.SceneContentRequest;
import com.iwrite.scene.dto.ScenePlanningRequest;
import com.iwrite.scene.dto.SceneRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.scene.dto.SceneUpdateRequest;
import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.sceneversion.dto.SceneVersionRestoreRequest;
import com.iwrite.sceneversion.entity.SceneVersion;
import com.iwrite.sceneversion.entity.SceneVersionSource;
import com.iwrite.sceneversion.service.SceneVersionService;
import com.iwrite.user.context.CurrentUserProvider;
import com.iwrite.writingprogress.ledger.entity.BookWordCountEvent;
import com.iwrite.writingprogress.ledger.entity.BookWordCountEventType;
import com.iwrite.writingprogress.ledger.repository.BookWordCountEventRepository;
import com.iwrite.writingprogress.ledger.service.WordCountEventCommand;
import com.iwrite.writingprogress.ledger.service.WordCountEventConflictException;
import com.iwrite.writingprogress.ledger.service.WordCountEventRecordResult;
import com.iwrite.writingprogress.ledger.service.WordCountEventService;
import com.iwrite.writingprogress.ledger.service.WordCountRequestFingerprint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Scene surfaces: the Manuscript read, the canonical content save, the Scene metadata and the Scene
 * half of Manuscript Structure Mutation.
 *
 * <p>Each of them names its own minimum capability (#207). Reading a Scene needs
 * {@code READ_MANUSCRIPT}. Creating, renaming, reordering, restating the status of or deleting one is a
 * Manuscript Structure Mutation, Owner-only in this partition. Saving canonical content is the
 * contextual {@code EDIT_AUTHORED_CONTRIBUTION}: Book scope only makes a User eligible, and
 * {@link SceneContentAuthority} still has to answer for the Scene itself.
 *
 * <p>Scene planning ({@code updatePlanning}) and Scene Version restore keep the pre-migration generic
 * guard on purpose: they belong to the Canonical Planning partition (#209) and the Scene Versions
 * partition (#210), and narrowing them here would decide those tickets by accident.
 */
@Service
public class SceneService {

    private final SceneRepository sceneRepository;
    private final ChapterService chapterService;
    private final WordCountService wordCountService;
    private final CharacterService characterService;
    private final LocationService locationService;
    private final ItemService itemService;
    private final ScenePlanningCompletenessService planningCompletenessService;
    private final SceneVersionService sceneVersionService;
    private final SceneDeletionLedgerService sceneDeletionLedgerService;
    private final WordCountEventService wordCountEventService;
    private final BookWordCountEventRepository wordCountEventRepository;
    private final BookAccessService bookAccessService;
    private final CurrentUserProvider currentUserProvider;
    private final BusinessTelemetry businessTelemetry;

    public SceneService(
            SceneRepository sceneRepository,
            ChapterService chapterService,
            WordCountService wordCountService,
            CharacterService characterService,
            LocationService locationService,
            ItemService itemService,
            ScenePlanningCompletenessService planningCompletenessService,
            SceneVersionService sceneVersionService,
            SceneDeletionLedgerService sceneDeletionLedgerService,
            WordCountEventService wordCountEventService,
            BookWordCountEventRepository wordCountEventRepository,
            BookAccessService bookAccessService,
            CurrentUserProvider currentUserProvider,
            BusinessTelemetry businessTelemetry
    ) {
        this.sceneRepository = sceneRepository;
        this.chapterService = chapterService;
        this.wordCountService = wordCountService;
        this.characterService = characterService;
        this.locationService = locationService;
        this.itemService = itemService;
        this.planningCompletenessService = planningCompletenessService;
        this.sceneVersionService = sceneVersionService;
        this.sceneDeletionLedgerService = sceneDeletionLedgerService;
        this.wordCountEventService = wordCountEventService;
        this.wordCountEventRepository = wordCountEventRepository;
        this.bookAccessService = bookAccessService;
        this.currentUserProvider = currentUserProvider;
        this.businessTelemetry = businessTelemetry;
    }

    @Transactional(readOnly = true)
    public SceneResponse findById(UUID sceneId) {
        return SceneResponse.fromEntity(getScene(sceneId));
    }

    @Transactional
    public SceneResponse create(UUID chapterId, SceneRequest request) {
        Chapter chapter = chapterService.getChapterForStructureMutation(chapterId);
        UUID bookId = chapter.getBook().getId();
        Book lockedBook = bookAccessService.requireCapabilityForUpdate(
                bookId,
                BookCapability.MUTATE_MANUSCRIPT_STRUCTURE
        );
        UUID operationId = request.operationId() == null ? UUID.randomUUID() : request.operationId();
        String requestFingerprint = WordCountRequestFingerprint.sceneCreate(
                currentUserProvider.userId(),
                bookId,
                chapterId,
                request.title(),
                request.summary(),
                request.status(),
                request.sortOrder(),
                request.contentJson(),
                request.contentText()
        );
        SceneResponse idempotentCreateResponse = idempotentCreateRetryResponse(bookId, operationId, requestFingerprint);
        if (idempotentCreateResponse != null) {
            return idempotentCreateResponse;
        }

        int totalBefore = Math.toIntExact(sceneRepository.sumWordCountByBookId(bookId));
        int newWordCount = wordCountService.countWords(request.contentText());

        Scene scene = new Scene();
        scene.setBook(lockedBook);
        scene.setChapter(chapter);
        scene.setTitle(request.title());
        scene.setSummary(request.summary());
        scene.setStatus(request.status() == null ? SceneStatus.IDEA : request.status());
        scene.setSortOrder(request.sortOrder() == null ? sceneRepository.countByChapterId(chapterId) : request.sortOrder());
        scene.setContentJson(request.contentJson());
        scene.setContentText(request.contentText());
        scene.setWordCount(newWordCount);
        scene.setContentRevision(0L);
        if (scene.getStatus() == SceneStatus.PLANNED) {
            rejectIncompletePlanning(scene);
        }

        Scene savedScene = sceneRepository.save(scene);
        if (newWordCount > 0 || request.operationId() != null) {
            recordFreshEvent(lockedBook, new WordCountEventCommand(
                    bookId,
                    savedScene.getId(),
                    savedScene.getId(),
                    savedScene.getTitle(),
                    BookWordCountEventType.CONTENT_SAVE,
                    newWordCount,
                    newWordCount,
                    operationId,
                    operationId,
                    null,
                    savedScene.getContentRevision(),
                    requestFingerprint,
                    totalBefore + newWordCount
            ));
        }

        return SceneResponse.fromEntity(savedScene);
    }

    @Transactional
    public SceneResponse update(UUID sceneId, SceneUpdateRequest request) {
        Scene scene = requireScene(sceneId, BookCapability.MUTATE_MANUSCRIPT_STRUCTURE);
        RequestValidation.rejectBlankWhenPresent("title", request.title());

        if (request.title() != null) {
            scene.setTitle(request.title());
        }
        if (request.summary() != null) {
            scene.setSummary(request.summary());
        }
        if (request.status() != null) {
            boolean enteringPlanned = scene.getStatus() != SceneStatus.PLANNED && request.status() == SceneStatus.PLANNED;
            if (enteringPlanned) {
                rejectIncompletePlanning(scene);
            }
            scene.setStatus(request.status());
        }
        if (request.sortOrder() != null) {
            scene.setSortOrder(request.sortOrder());
        }

        return SceneResponse.fromEntity(scene);
    }

    /*
     * Telemetry wrapper only: the business body below is unchanged, and the
     * span is a child of whatever HTTP trace is active. See
     * docs/otel-business-signals.md.
     *
     * The span ends on transaction completion, not on method return: this
     * method runs inside the @Transactional proxy's transaction, so ending
     * it here would report success before flush/commit has actually
     * happened. The Scope, however, is always detached here in the
     * finally block, whether or not the span itself ends now: a Scope only
     * makes sense for the lexical duration of this method, and leaving it
     * current until commit would make unrelated later work a child of this
     * span.
     */
    @Transactional
    public SceneResponse updateContent(UUID sceneId, SceneContentRequest request) {
        BusinessTelemetry.Operation telemetry = businessTelemetry.sceneContentSave();
        boolean endsWithTransaction = telemetry.deferEndToTransaction();
        try {
            return updateContent(sceneId, request, telemetry);
        } catch (ConflictException conflict) {
            telemetry.failure(BusinessTelemetry.RESULT_CONFLICT, conflict);
            throw conflict;
        } catch (BadRequestException validationFailure) {
            telemetry.failure(BusinessTelemetry.RESULT_VALIDATION_ERROR, validationFailure);
            throw validationFailure;
        } catch (ResourceNotFoundException notFound) {
            telemetry.failure(BusinessTelemetry.RESULT_NOT_FOUND, notFound);
            throw notFound;
        } catch (RuntimeException failure) {
            telemetry.failure(BusinessTelemetry.RESULT_FAILURE, failure);
            throw failure;
        } finally {
            telemetry.detachScope();
            if (!endsWithTransaction) {
                telemetry.close();
            }
        }
    }

    private SceneResponse updateContent(
            UUID sceneId,
            SceneContentRequest request,
            BusinessTelemetry.Operation telemetry
    ) {
        Scene scene = requireSceneForContentUpdate(sceneId);
        rejectMissingOperationId(request.operationId());
        Book lockedBook = requireSceneContentAuthority(scene);
        SceneVersionSource source = contentSource(request.source());
        telemetry.attribute(BusinessTelemetry.SCENE_SOURCE, telemetrySource(source));
        telemetry.attribute(
                BusinessTelemetry.SCENE_CONTENT_SIZE_BUCKET,
                BusinessTelemetry.contentSizeBucket(request.contentText())
        );
        String requestFingerprint = WordCountRequestFingerprint.contentSave(
                currentUserProvider.userId(),
                lockedBook.getId(),
                scene.getId(),
                request.expectedContentRevision(),
                source,
                request.contentJson(),
                request.contentText()
        );
        SceneResponse idempotentRetryResponse = idempotentRetryResponse(scene, request.operationId(), requestFingerprint);
        if (idempotentRetryResponse != null) {
            telemetry.result(BusinessTelemetry.RESULT_IDEMPOTENT_RETRY)
                    .attribute(BusinessTelemetry.SCENE_CONTENT_CHANGED, false);
            return idempotentRetryResponse;
        }
        rejectStaleContentRevision(scene, request.expectedContentRevision());
        if (sameContent(scene, request.contentJson(), request.contentText())) {
            telemetry.result(BusinessTelemetry.RESULT_NO_CHANGE)
                    .attribute(BusinessTelemetry.SCENE_CONTENT_CHANGED, false);
            long revision = scene.getContentRevision();
            recordFreshEvent(lockedBook, new WordCountEventCommand(
                    lockedBook.getId(),
                    scene.getId(),
                    scene.getId(),
                    scene.getTitle(),
                    BookWordCountEventType.CONTENT_SAVE,
                    0,
                    0,
                    request.operationId(),
                    request.operationId(),
                    revision,
                    revision,
                    requestFingerprint,
                    Math.toIntExact(sceneRepository.sumWordCountByBookId(lockedBook.getId()))
            ));
            return SceneResponse.fromEntity(scene);
        }

        telemetry.attribute(BusinessTelemetry.SCENE_CONTENT_CHANGED, true);
        UUID bookId = lockedBook.getId();
        int totalBefore = Math.toIntExact(sceneRepository.sumWordCountByBookId(bookId));
        int oldWordCount = wordCount(scene);
        int newWordCount = wordCountService.countWords(request.contentText());
        int wordCountDelta = newWordCount - oldWordCount;
        long revisionBefore = scene.getContentRevision();

        sceneVersionService.checkpointBeforeContentOverwrite(scene, source);
        scene.setContentJson(request.contentJson());
        scene.setContentText(request.contentText());
        scene.setWordCount(newWordCount);
        scene.incrementContentRevision();
        if (source == SceneVersionSource.MANUAL_SAVE) {
            sceneVersionService.checkpointAfterManualContentSave(scene);
        }
        recordFreshEvent(lockedBook, new WordCountEventCommand(
                bookId,
                scene.getId(),
                scene.getId(),
                scene.getTitle(),
                BookWordCountEventType.CONTENT_SAVE,
                wordCountDelta,
                wordCountDelta,
                request.operationId(),
                request.operationId(),
                revisionBefore,
                scene.getContentRevision(),
                requestFingerprint,
                totalBefore + wordCountDelta
        ));

        return SceneResponse.fromEntity(scene);
    }

    @Transactional
    public SceneResponse restoreVersion(UUID sceneId, UUID versionId, SceneVersionRestoreRequest request) {
        Scene scene = getSceneForLegacyUpdate(sceneId);
        rejectMissingOperationId(request.operationId());
        Book lockedBook = bookAccessService.requireBookEditAccessForUpdate(scene.getBook().getId());
        SceneVersion version = sceneVersionService.getCurrentSceneVersion(sceneId, versionId);
        String requestFingerprint = WordCountRequestFingerprint.versionRestore(
                currentUserProvider.userId(),
                lockedBook.getId(),
                scene.getId(),
                versionId,
                request.expectedContentRevision()
        );
        SceneResponse idempotentRetryResponse = idempotentRestoreRetryResponse(scene, request.operationId(), requestFingerprint);
        if (idempotentRetryResponse != null) {
            return idempotentRetryResponse;
        }
        rejectStaleContentRevision(scene, request.expectedContentRevision());

        if (sameContent(scene, version.getContentJson(), version.getContentText())) {
            return SceneResponse.fromEntity(scene);
        }

        UUID bookId = lockedBook.getId();
        int totalBefore = Math.toIntExact(sceneRepository.sumWordCountByBookId(bookId));
        int previousWordCount = wordCount(scene);
        int newWordCount = wordCountService.countWords(version.getContentText());
        int manuscriptWordDelta = newWordCount - previousWordCount;
        long revisionBefore = scene.getContentRevision();

        sceneVersionService.checkpointBeforeRestore(scene);
        scene.setContentJson(version.getContentJson());
        scene.setContentText(version.getContentText());
        scene.setWordCount(newWordCount);
        scene.incrementContentRevision();
        recordFreshEvent(lockedBook, new WordCountEventCommand(
                bookId,
                scene.getId(),
                scene.getId(),
                scene.getTitle(),
                BookWordCountEventType.VERSION_RESTORE,
                0,
                manuscriptWordDelta,
                request.operationId(),
                request.operationId(),
                revisionBefore,
                scene.getContentRevision(),
                requestFingerprint,
                totalBefore + manuscriptWordDelta
        ));

        return SceneResponse.fromEntity(scene);
    }

    @Transactional
    public SceneResponse updatePlanning(UUID sceneId, ScenePlanningRequest request) {
        Scene scene = getSceneForLegacyEdit(sceneId);
        UUID bookId = scene.getBook().getId();
        List<String> gapsBefore = scene.getStatus() == SceneStatus.PLANNED
                ? planningCompletenessService.planningGaps(scene)
                : List.of();
        Character povCharacter = findCharacterForBook(bookId, request.povCharacterId(), "povCharacterId");
        Location mainLocation = findLocationForBook(bookId, request.mainLocationId());
        Set<Character> participantCharacters = findParticipantsForBook(bookId, request.participantCharacterIds());
        Set<Item> items = findItemsForBook(bookId, request.itemIds());

        scene.setGoal(request.goal());
        scene.setConflict(request.conflict());
        scene.setOutcome(request.outcome());
        scene.setPlanningNotes(request.planningNotes());
        scene.setPovCharacter(povCharacter);
        scene.setMainLocation(mainLocation);
        scene.setParticipantCharacters(participantCharacters);
        scene.setItems(items);
        if (scene.getStatus() == SceneStatus.PLANNED) {
            rejectIntroducedPlanningGaps(scene, gapsBefore);
        }

        return SceneResponse.fromEntity(scene);
    }

    @Transactional
    public void delete(UUID sceneId) {
        Scene scene = requireSceneForUpdate(sceneId, BookCapability.MUTATE_MANUSCRIPT_STRUCTURE);
        Book lockedBook = bookAccessService.requireCapabilityForUpdate(
                scene.getBook().getId(),
                BookCapability.MUTATE_MANUSCRIPT_STRUCTURE
        );
        sceneDeletionLedgerService.prepareSceneDelete(scene, lockedBook);
        sceneRepository.delete(scene);
    }

    @Transactional
    public void reorder(UUID chapterId, ReorderRequest request) {
        chapterService.getChapterForStructureMutation(chapterId);
        List<Scene> scenes = sceneRepository.findByChapterIdOrderBySortOrderAsc(chapterId);
        applyReorder(scenes, request.orderedIds(), Scene::getId, Scene::setSortOrder, "scenes");
    }

    /** Reads a Scene the current User may read the Manuscript of. */
    @Transactional(readOnly = true)
    public Scene getScene(UUID sceneId) {
        return requireScene(sceneId, BookCapability.READ_MANUSCRIPT);
    }

    /**
     * Resolves a Scene by tenant and then proves the capability on its Book.
     *
     * <p>A Scene of another Workspace, of a Book with no relationship, and one whose Book denies the
     * capability all raise the same {@code Scene not found}: a Scene identifier must not become a way
     * to learn that a Scene, a Chapter or a Book exists.
     */
    private Scene requireScene(UUID sceneId, BookCapability capability) {
        Scene scene = sceneRepository.findByIdAndTenantId(sceneId, currentUserProvider.tenantId())
                .orElseThrow(() -> sceneNotFound(sceneId));
        requireSceneBookCapability(scene, sceneId, capability);
        return scene;
    }

    /** Same proof as {@link #requireScene(UUID, BookCapability)}, taking the Scene row lock first. */
    private Scene requireSceneForUpdate(UUID sceneId, BookCapability capability) {
        Scene scene = sceneRepository.findByIdAndTenantIdForUpdate(sceneId, currentUserProvider.tenantId())
                .orElseThrow(() -> sceneNotFound(sceneId));
        requireSceneBookCapability(scene, sceneId, capability);
        return scene;
    }

    /**
     * Locks the Scene for a canonical content save and proves Book-scoped eligibility for
     * {@code EDIT_AUTHORED_CONTRIBUTION}. Eligibility alone authorizes nothing: the caller must still
     * pass {@link #requireSceneContentAuthority(Scene)} before the content is touched.
     */
    private Scene requireSceneForContentUpdate(UUID sceneId) {
        Scene scene = sceneRepository.findByIdAndTenantIdForUpdate(sceneId, currentUserProvider.tenantId())
                .orElseThrow(() -> sceneNotFound(sceneId));
        try {
            bookAccessService.requireCapabilityEligibility(
                    scene.getBook().getId(),
                    BookCapability.EDIT_AUTHORED_CONTRIBUTION
            );
        } catch (ResourceNotFoundException exception) {
            throw sceneNotFound(sceneId);
        }
        return scene;
    }

    /**
     * Takes the Book lock for the content save, re-proves eligibility under it, and then evaluates the
     * resource-scoped predicate that {@code EDIT_AUTHORED_CONTRIBUTION} still requires.
     *
     * <p>A User who is merely eligible is refused with the same {@code Scene not found} as one who
     * cannot see the Scene at all, so the refusal never reports which half of the rule failed.
     */
    private Book requireSceneContentAuthority(Scene scene) {
        BookAccessService.AccessibleBook accessible = bookAccessService.requireCapabilityEligibilityForUpdate(
                scene.getBook().getId(),
                BookCapability.EDIT_AUTHORED_CONTRIBUTION
        );
        if (!SceneContentAuthority.canEditSceneContent(accessible.access())) {
            throw sceneNotFound(scene.getId());
        }
        return accessible.book();
    }

    private void requireSceneBookCapability(Scene scene, UUID sceneId, BookCapability capability) {
        try {
            bookAccessService.requireCapability(scene.getBook().getId(), capability);
        } catch (ResourceNotFoundException exception) {
            throw sceneNotFound(sceneId);
        }
    }

    /** Pre-migration guard kept for the Scene Version restore surface of #210. */
    private Scene getSceneForLegacyUpdate(UUID sceneId) {
        Scene scene = sceneRepository.findByIdAndTenantIdForUpdate(sceneId, currentUserProvider.tenantId())
                .orElseThrow(() -> sceneNotFound(sceneId));
        requireSceneBookLegacyEditAccess(scene, sceneId);
        return scene;
    }

    /** Pre-migration guard kept for the Scene planning surface of #209. */
    private Scene getSceneForLegacyEdit(UUID sceneId) {
        Scene scene = sceneRepository.findByIdAndTenantId(sceneId, currentUserProvider.tenantId())
                .orElseThrow(() -> sceneNotFound(sceneId));
        requireSceneBookLegacyEditAccess(scene, sceneId);
        return scene;
    }

    private void requireSceneBookLegacyEditAccess(Scene scene, UUID sceneId) {
        try {
            bookAccessService.requireBookEditAccess(scene.getBook().getId());
        } catch (ResourceNotFoundException exception) {
            throw sceneNotFound(sceneId);
        }
    }

    private ResourceNotFoundException sceneNotFound(UUID sceneId) {
        return new ResourceNotFoundException("Scene not found: " + sceneId);
    }

    private Character findCharacterForBook(UUID bookId, UUID characterId, String fieldName) {
        if (characterId == null) {
            return null;
        }

        Character character = characterService.getCharacter(characterId);
        if (!character.getBook().getId().equals(bookId)) {
            throw new BadRequestException(fieldName + " must belong to the same book as the scene");
        }

        return character;
    }

    private Location findLocationForBook(UUID bookId, UUID locationId) {
        if (locationId == null) {
            return null;
        }

        Location location = locationService.getLocation(locationId);
        if (!location.getBook().getId().equals(bookId)) {
            throw new BadRequestException("mainLocationId must belong to the same book as the scene");
        }

        return location;
    }

    private Item findItemForBook(UUID bookId, UUID itemId) {
        Item item = itemService.getItem(itemId);
        if (!item.getBook().getId().equals(bookId)) {
            throw new BadRequestException("itemIds must belong to the same book as the scene");
        }

        return item;
    }

    private Set<Character> findParticipantsForBook(UUID bookId, List<UUID> participantCharacterIds) {
        rejectNullIds("participantCharacterIds", participantCharacterIds);
        rejectDuplicateIds("participantCharacterIds", participantCharacterIds);

        return participantCharacterIds.stream()
                .map(characterId -> findCharacterForBook(bookId, characterId, "participantCharacterIds"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<Item> findItemsForBook(UUID bookId, List<UUID> itemIds) {
        rejectNullIds("itemIds", itemIds);
        rejectDuplicateIds("itemIds", itemIds);

        return itemIds.stream()
                .map(itemId -> findItemForBook(bookId, itemId))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void rejectDuplicateIds(String fieldName, List<UUID> ids) {
        if (ids.size() != new HashSet<>(ids).size()) {
            throw new BadRequestException(fieldName + " must not contain duplicate IDs");
        }
    }

    private void rejectNullIds(String fieldName, List<UUID> ids) {
        if (ids.stream().anyMatch(id -> id == null)) {
            throw new BadRequestException(fieldName + " must not contain null IDs");
        }
    }

    private <T> void applyReorder(
            List<T> children,
            List<UUID> orderedIds,
            Function<T, UUID> idGetter,
            OrderSetter<T> orderSetter,
            String childName
    ) {
        if (orderedIds.size() != new HashSet<>(orderedIds).size()) {
            throw new BadRequestException("Duplicate IDs are not allowed");
        }
        if (orderedIds.size() != children.size()) {
            throw new BadRequestException("Reorder list must include all " + childName + " for the parent");
        }

        Map<UUID, T> childrenById = children.stream()
                .collect(Collectors.toMap(idGetter, Function.identity()));

        if (!childrenById.keySet().equals(new HashSet<>(orderedIds))) {
            throw new BadRequestException("All IDs must exist and belong to the parent");
        }

        for (int index = 0; index < orderedIds.size(); index++) {
            T child = childrenById.get(orderedIds.get(index));
            orderSetter.setSortOrder(child, index);
        }
    }

    private int wordCount(Scene scene) {
        return scene.getWordCount() == null ? 0 : scene.getWordCount();
    }

    private void rejectStaleContentRevision(Scene scene, Long expectedContentRevision) {
        if (expectedContentRevision == null) {
            throw new BadRequestException("expectedContentRevision is required");
        }
        if (!expectedContentRevision.equals(scene.getContentRevision())) {
            throw new ConflictException("Scene content has changed. Reload the scene before saving.");
        }
    }

    private void rejectMissingOperationId(UUID operationId) {
        if (operationId == null) {
            throw new BadRequestException("operationId is required");
        }
    }

    private SceneVersionSource contentSource(SceneVersionSource source) {
        if (source == null) {
            return SceneVersionSource.AUTO_SAVE;
        }
        if (source != SceneVersionSource.AUTO_SAVE && source != SceneVersionSource.MANUAL_SAVE) {
            throw new BadRequestException("source must be AUTO_SAVE or MANUAL_SAVE");
        }
        return source;
    }

    /* Enum name -> controlled telemetry token, so a new enum constant cannot leak. */
    private static String telemetrySource(SceneVersionSource source) {
        return switch (source) {
            case MANUAL_SAVE -> BusinessTelemetry.SOURCE_MANUAL_SAVE;
            case AUTO_SAVE -> BusinessTelemetry.SOURCE_AUTOSAVE;
            case RESTORE_SAFETY -> BusinessTelemetry.SOURCE_RESTORE;
            case DELETE_SAFETY -> BusinessTelemetry.SOURCE_OTHER;
        };
    }

    private boolean sameContent(Scene scene, String contentJson, String contentText) {
        return normalized(scene.getContentJson()).equals(normalized(contentJson))
                && normalized(scene.getContentText()).equals(normalized(contentText));
    }

    private SceneResponse idempotentCreateRetryResponse(UUID bookId, UUID idempotencyKey, String requestFingerprint) {
        return wordCountEventRepository.findByBookIdAndIdempotencyKey(bookId, idempotencyKey)
                .map(event -> {
                    requireMatchingFingerprint(
                            event,
                            requestFingerprint,
                            "Idempotency key was already used for a different scene creation."
                    );
                    if (event.getScene() == null) {
                        throw new ResourceNotFoundException("Scene not found for idempotent create retry.");
                    }
                    return SceneResponse.fromEntity(event.getScene());
                })
                .orElse(null);
    }

    private SceneResponse idempotentRetryResponse(Scene scene, UUID idempotencyKey, String requestFingerprint) {
        return wordCountEventRepository.findByBookIdAndIdempotencyKey(scene.getBook().getId(), idempotencyKey)
                .map(event -> {
                    requireMatchingFingerprint(
                            event,
                            requestFingerprint,
                            "Idempotency key was already used for a different scene content update."
                    );
                    return SceneResponse.fromEntity(scene);
                })
                .orElse(null);
    }

    private SceneResponse idempotentRestoreRetryResponse(
            Scene scene,
            UUID idempotencyKey,
            String requestFingerprint
    ) {
        return wordCountEventRepository.findByBookIdAndIdempotencyKey(scene.getBook().getId(), idempotencyKey)
                .map(event -> {
                    requireMatchingFingerprint(
                            event,
                            requestFingerprint,
                            "Idempotency key was already used for a different scene version restore."
                    );
                    return SceneResponse.fromEntity(scene);
                })
                .orElse(null);
    }

    private void requireMatchingFingerprint(
            BookWordCountEvent event,
            String requestFingerprint,
            String conflictMessage
    ) {
        if (event.getRequestFingerprint() == null) {
            throw new WordCountEventConflictException(
                    "Legacy idempotency event cannot prove a matching retry. Please reload and retry with a new operation."
            );
        }
        if (!event.getRequestFingerprint().equals(requestFingerprint)) {
            throw new WordCountEventConflictException(conflictMessage);
        }
    }

    private void recordFreshEvent(Book lockedBook, WordCountEventCommand command) {
        WordCountEventRecordResult result = wordCountEventService.recordForLockedBook(lockedBook, command);
        if (result == WordCountEventRecordResult.ALREADY_RECORDED) {
            throw new WordCountEventConflictException("Idempotency key was already used for a different word-count event.");
        }
    }

    private String normalized(String value) {
        return value == null ? "" : value;
    }

    private void rejectIncompletePlanning(Scene scene) {
        if (!planningCompletenessService.isComplete(scene)) {
            throw new BadRequestException(
                    "Scene status PLANNED requires complete planning. Missing fields: "
                            + planningCompletenessService.formatMissingPlanningFields(scene)
            );
        }
    }

    private void rejectIntroducedPlanningGaps(Scene scene, List<String> gapsBefore) {
        List<String> gapsAfter = planningCompletenessService.planningGaps(scene);
        if (gapsAfter.stream().anyMatch(gap -> !gapsBefore.contains(gap))) {
            throw new BadRequestException(
                    "Scene status PLANNED cannot lose required planning fields. Missing fields: "
                            + planningCompletenessService.formatMissingPlanningFields(scene)
            );
        }
    }

    @FunctionalInterface
    private interface OrderSetter<T> {
        void setSortOrder(T child, int sortOrder);
    }
}
