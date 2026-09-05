package com.iwrite.dashboard.service;

import com.iwrite.book.authorization.BookAccessContext;
import com.iwrite.book.authorization.BookCapability;
import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookAccessService;
import com.iwrite.chapter.entity.Chapter;
import com.iwrite.chapter.repository.ChapterRepository;
import com.iwrite.character.entity.Character;
import com.iwrite.dashboard.dto.BookDashboardResponse;
import com.iwrite.dashboard.dto.BookMyWritingResponse;
import com.iwrite.dashboard.dto.DashboardSceneSummaryResponse;
import com.iwrite.dashboard.dto.DailyWritingProgressResponse;
import com.iwrite.dashboard.dto.EntityUsageResponse;
import com.iwrite.dashboard.dto.NarrativeGapsResponse;
import com.iwrite.dashboard.dto.PlanningProgressResponse;
import com.iwrite.dashboard.dto.PovStatsResponse;
import com.iwrite.dashboard.dto.StatusCountResponse;
import com.iwrite.dashboard.dto.WritingConsistencyResponse;
import com.iwrite.dashboard.dto.WritingProgressDashboardResponse;
import com.iwrite.dashboard.dto.WritingScheduleResponse;
import com.iwrite.item.entity.Item;
import com.iwrite.location.entity.Location;
import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.scene.service.ScenePlanningCompletenessService;
import com.iwrite.section.entity.BookSection;
import com.iwrite.section.repository.BookSectionRepository;
import com.iwrite.writingprogress.entity.DailyWritingProgress;
import com.iwrite.writingprogress.service.DailyWritingProgressService;
import com.iwrite.writingprogress.service.PersonalBookWritingGoalService;
import com.iwrite.writingprogress.service.PersonalBookWritingGoalSnapshot;
import com.iwrite.writingprogress.service.WritingScheduleService;
import com.iwrite.writingprogress.service.WritingProgressPeriod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class BookDashboardService {

    private final BookAccessService bookAccessService;
    private final BookSectionRepository sectionRepository;
    private final ChapterRepository chapterRepository;
    private final SceneRepository sceneRepository;
    private final DailyWritingProgressService dailyWritingProgressService;
    private final WritingScheduleService writingScheduleService;
    private final PersonalBookWritingGoalService personalBookWritingGoalService;
    private final ScenePlanningCompletenessService planningCompletenessService;

    public BookDashboardService(
            BookAccessService bookAccessService,
            BookSectionRepository sectionRepository,
            ChapterRepository chapterRepository,
            SceneRepository sceneRepository,
            DailyWritingProgressService dailyWritingProgressService,
            WritingScheduleService writingScheduleService,
            PersonalBookWritingGoalService personalBookWritingGoalService,
            ScenePlanningCompletenessService planningCompletenessService
    ) {
        this.bookAccessService = bookAccessService;
        this.sectionRepository = sectionRepository;
        this.chapterRepository = chapterRepository;
        this.sceneRepository = sceneRepository;
        this.dailyWritingProgressService = dailyWritingProgressService;
        this.writingScheduleService = writingScheduleService;
        this.personalBookWritingGoalService = personalBookWritingGoalService;
        this.planningCompletenessService = planningCompletenessService;
    }

    @Transactional
    public BookDashboardResponse getDashboard(UUID bookId) {
        return getDashboard(bookId, WritingProgressPeriod.DEFAULT);
    }

    @Transactional
    public BookDashboardResponse getDashboard(UUID bookId, WritingProgressPeriod progressPeriod) {
        BookAccessService.AccessibleBook accessible = bookAccessService.resolveAccessibleBook(bookId);
        Book book = accessible.book();
        BookAccessContext access = accessible.access();
        List<Scene> scenes = sceneRepository.findByBookIdOrderBySortOrderAsc(bookId);

        int totalScenes = scenes.size();
        int totalWordCount = scenes.stream()
                .mapToInt(this::wordCount)
                .sum();
        int plannedScenesCount = (int) scenes.stream()
                .filter(this::isPlanned)
                .count();
        boolean managesOwnGoal = access.isGranted(BookCapability.MANAGE_OWN_PERSONAL_WRITING_GOAL);
        // Read once and shared by both halves of the projection below. The target and the revision a
        // save is decided against must describe the same state of the same goal: read separately, a
        // save committing between them would leave this response quoting a revision for a target it
        // never showed, and a save made against it would be accepted over that newer choice.
        PersonalBookWritingGoalSnapshot personalGoal = managesOwnGoal
                ? personalBookWritingGoalService.snapshotFor(access.bookId(), access.userId())
                : null;

        return new BookDashboardResponse(
                book.getId(),
                book.getTitle(),
                totalWordCount,
                book.getTargetWordCount(),
                managesOwnGoal ? personalGoal.dailyTargetWordCount() : null,
                remainingWordCount(totalWordCount, book.getTargetWordCount()),
                wordCountProgressPercent(totalWordCount, book.getTargetWordCount()),
                exceededTargetWordCount(totalWordCount, book.getTargetWordCount()),
                sectionRepository.countByBookId(bookId),
                chapterRepository.countByBookId(bookId),
                totalScenes,
                managesOwnGoal ? buildMyWriting(book, totalWordCount, progressPeriod, personalGoal) : null,
                new PlanningProgressResponse(plannedScenesCount, totalScenes, plannedScenesPercent(plannedScenesCount, totalScenes)),
                buildStatusCounts(scenes),
                buildPovStats(scenes),
                buildNarrativeGaps(scenes),
                buildCharacterUsage(scenes),
                buildLocationUsage(scenes),
                buildItemUsage(scenes),
                sortedCapabilities(access.capabilities()),
                sortedCapabilities(access.contextualCapabilities())
        );
    }

    /**
     * The caller's own Personal Book Writing Goal projection, built only for a role the policy lets
     * have one at all.
     *
     * <p>The whole block is the goal, not just the daily target beside it: the per-day
     * {@code dailyTargetWordCount} snapshots, the planned writing days and the consistency measured
     * against them are all state {@code /writing-goal} is non-enumerable for without
     * {@link BookCapability#MANAGE_OWN_PERSONAL_WRITING_GOAL}. Projecting it here would hand a
     * demoted collaborator, whose goal row survived the role change, the same state through a
     * different door.
     *
     * <p>Not building it also keeps a denied read a read: the routine is created lazily by the first
     * call that needs it, so a dashboard that always built this block would persist a default
     * schedule for a User the policy says keeps no personal routine.
     */
    private BookMyWritingResponse buildMyWriting(
            Book book,
            int totalWordCount,
            WritingProgressPeriod progressPeriod,
            PersonalBookWritingGoalSnapshot personalGoal
    ) {
        return new BookMyWritingResponse(
                buildWritingProgress(book.getId(), totalWordCount, progressPeriod),
                buildWritingSchedule(book),
                personalGoal.revision()
        );
    }

    private List<BookCapability> sortedCapabilities(Set<BookCapability> capabilities) {
        return capabilities.stream().sorted(Comparator.comparing(Enum::name)).toList();
    }

    private WritingProgressDashboardResponse buildWritingProgress(UUID bookId, int totalWordCount, WritingProgressPeriod progressPeriod) {
        DailyWritingProgress today = dailyWritingProgressService.getTodayProgressOrEmpty(bookId, totalWordCount);
        List<DailyWritingProgressResponse> recentDays = dailyWritingProgressService.getRecentProgress(bookId, progressPeriod)
                .stream()
                .map(this::toDailyWritingProgressResponse)
                .toList();
        WritingConsistencyResponse consistency = dailyWritingProgressService.getWritingConsistency(bookId, progressPeriod);

        return new WritingProgressDashboardResponse(toDailyWritingProgressResponse(today), recentDays, consistency);
    }

    private WritingScheduleResponse buildWritingSchedule(Book book) {
        UUID bookId = book.getId();
        var activeSchedule = writingScheduleService.getOrCreateActiveScheduleForCurrentUser(book);
        List<java.time.DayOfWeek> plannedWritingDays = writingScheduleService.orderedDays(activeSchedule.getPlannedDays());
        LocalDate today = dailyWritingProgressService.today();
        boolean todayPlannedWritingDay = writingScheduleService.getScheduleForDate(book, today)
                .getPlannedDays()
                .contains(today.getDayOfWeek());

        return new WritingScheduleResponse(
                plannedWritingDays,
                plannedWritingDays.size(),
                writingScheduleService.restDays(plannedWritingDays),
                todayPlannedWritingDay,
                activeSchedule.getEffectiveFrom()
        );
    }

    private DailyWritingProgressResponse toDailyWritingProgressResponse(DailyWritingProgress progress) {
        return new DailyWritingProgressResponse(
                progress.getProgressDate(),
                progress.getDailyTargetWordCount(),
                progress.getStartingManuscriptWordCount(),
                progress.getEndingManuscriptWordCount(),
                progress.getProductiveWordCountChange(),
                progress.getManuscriptAdjustmentWordCount(),
                dailyProgressPercent(progress.getProductiveWordCountChange(), progress.getDailyTargetWordCount())
        );
    }

    private List<StatusCountResponse> buildStatusCounts(List<Scene> scenes) {
        Map<SceneStatus, CountStats> statsByStatus = new EnumMap<>(SceneStatus.class);
        for (SceneStatus status : SceneStatus.values()) {
            statsByStatus.put(status, new CountStats());
        }

        for (Scene scene : scenes) {
            CountStats stats = statsByStatus.get(scene.getStatus());
            stats.add(scene);
        }

        return statsByStatus.entrySet()
                .stream()
                .map(entry -> new StatusCountResponse(
                        entry.getKey(),
                        entry.getValue().scenesCount,
                        entry.getValue().wordCount,
                        entry.getValue().scenes
                ))
                .toList();
    }

    private List<PovStatsResponse> buildPovStats(List<Scene> scenes) {
        Map<UUID, NamedCountStats> statsByCharacterId = new HashMap<>();

        for (Scene scene : scenes) {
            Character povCharacter = scene.getPovCharacter();
            if (povCharacter == null) {
                continue;
            }

            statsByCharacterId
                    .computeIfAbsent(povCharacter.getId(), id -> new NamedCountStats(id, povCharacter.getName()))
                    .add(wordCount(scene));
        }

        return statsByCharacterId.values()
                .stream()
                .sorted(namedStatsComparator())
                .map(stats -> new PovStatsResponse(stats.id, stats.name, stats.scenesCount, stats.wordCount))
                .toList();
    }

    private NarrativeGapsResponse buildNarrativeGaps(List<Scene> scenes) {
        int scenesWithoutPov = 0;
        int scenesWithoutGoal = 0;
        int scenesWithoutConflict = 0;
        int scenesWithoutOutcome = 0;
        int scenesWithoutMainLocation = 0;
        int scenesWithoutParticipants = 0;

        for (Scene scene : scenes) {
            if (scene.getPovCharacter() == null) {
                scenesWithoutPov++;
            }
            if (!hasText(scene.getGoal())) {
                scenesWithoutGoal++;
            }
            if (!hasText(scene.getConflict())) {
                scenesWithoutConflict++;
            }
            if (!hasText(scene.getOutcome())) {
                scenesWithoutOutcome++;
            }
            if (scene.getMainLocation() == null) {
                scenesWithoutMainLocation++;
            }
            if (scene.getParticipantCharacters().isEmpty()) {
                scenesWithoutParticipants++;
            }
        }

        return new NarrativeGapsResponse(
                scenesWithoutPov,
                scenesWithoutGoal,
                scenesWithoutConflict,
                scenesWithoutOutcome,
                scenesWithoutMainLocation,
                scenesWithoutParticipants
        );
    }

    private List<EntityUsageResponse> buildCharacterUsage(List<Scene> scenes) {
        Map<UUID, NamedCountStats> statsByCharacterId = new HashMap<>();

        for (Scene scene : scenes) {
            for (Character character : scene.getParticipantCharacters()) {
                statsByCharacterId
                        .computeIfAbsent(character.getId(), id -> new NamedCountStats(id, character.getName()))
                        .addScene();
            }
        }

        return toEntityUsage(statsByCharacterId);
    }

    private List<EntityUsageResponse> buildLocationUsage(List<Scene> scenes) {
        Map<UUID, NamedCountStats> statsByLocationId = new HashMap<>();

        for (Scene scene : scenes) {
            Location location = scene.getMainLocation();
            if (location == null) {
                continue;
            }

            statsByLocationId
                    .computeIfAbsent(location.getId(), id -> new NamedCountStats(id, location.getName()))
                    .addScene();
        }

        return toEntityUsage(statsByLocationId);
    }

    private List<EntityUsageResponse> buildItemUsage(List<Scene> scenes) {
        Map<UUID, NamedCountStats> statsByItemId = new HashMap<>();

        for (Scene scene : scenes) {
            for (Item item : scene.getItems()) {
                statsByItemId
                        .computeIfAbsent(item.getId(), id -> new NamedCountStats(id, item.getName()))
                        .addScene();
            }
        }

        return toEntityUsage(statsByItemId);
    }

    private List<EntityUsageResponse> toEntityUsage(Map<UUID, NamedCountStats> statsById) {
        return statsById.values()
                .stream()
                .sorted(namedStatsComparator())
                .map(stats -> new EntityUsageResponse(stats.id, stats.name, stats.scenesCount))
                .toList();
    }

    private Comparator<NamedCountStats> namedStatsComparator() {
        return Comparator
                .comparingInt((NamedCountStats stats) -> stats.scenesCount)
                .reversed()
                .thenComparing(stats -> stats.name)
                .thenComparing(stats -> stats.id);
    }

    private boolean isPlanned(Scene scene) {
        return planningCompletenessService.isComplete(scene);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private int wordCount(Scene scene) {
        return scene.getWordCount() == null ? 0 : scene.getWordCount();
    }

    private DashboardSceneSummaryResponse toSceneSummary(Scene scene) {
        Chapter chapter = scene.getChapter();
        BookSection section = chapter.getSection();

        return new DashboardSceneSummaryResponse(
                scene.getId(),
                scene.getTitle(),
                scene.getSummary(),
                scene.getStatus(),
                wordCount(scene),
                chapter.getId(),
                chapter.getTitle(),
                section.getId(),
                section.getTitle(),
                scene.getPovCharacter() == null ? null : scene.getPovCharacter().getName(),
                scene.getMainLocation() == null ? null : scene.getMainLocation().getName(),
                scene.getParticipantCharacters()
                        .stream()
                        .map(Character::getName)
                        .sorted()
                        .toList(),
                scene.getItems()
                        .stream()
                        .map(Item::getName)
                        .sorted()
                        .toList(),
                scene.getGoal(),
                scene.getConflict(),
                scene.getOutcome(),
                scene.getPlanningNotes()
        );
    }

    private double plannedScenesPercent(int plannedScenesCount, int totalScenes) {
        if (totalScenes == 0) {
            return 0.0;
        }

        return (plannedScenesCount * 100.0) / totalScenes;
    }

    private Integer remainingWordCount(int totalWordCount, Integer targetWordCount) {
        if (!hasValidTargetWordCount(targetWordCount)) {
            return null;
        }

        return Math.max(targetWordCount - totalWordCount, 0);
    }

    private Double wordCountProgressPercent(int totalWordCount, Integer targetWordCount) {
        if (!hasValidTargetWordCount(targetWordCount)) {
            return null;
        }

        return (totalWordCount * 100.0) / targetWordCount;
    }

    private Integer exceededTargetWordCount(int totalWordCount, Integer targetWordCount) {
        if (!hasValidTargetWordCount(targetWordCount)) {
            return null;
        }

        return Math.max(totalWordCount - targetWordCount, 0);
    }

    private Double dailyProgressPercent(int productiveWordCountChange, Integer dailyTargetWordCount) {
        if (!hasValidTargetWordCount(dailyTargetWordCount)) {
            return null;
        }

        return (productiveWordCountChange * 100.0) / dailyTargetWordCount;
    }

    private boolean hasValidTargetWordCount(Integer targetWordCount) {
        return targetWordCount != null && targetWordCount > 0;
    }

    private class CountStats {
        private int scenesCount;
        private int wordCount;
        private final List<DashboardSceneSummaryResponse> scenes = new java.util.ArrayList<>();

        void add(Scene scene) {
            scenesCount++;
            wordCount += wordCount(scene);
            scenes.add(toSceneSummary(scene));
        }
    }

    private static class NamedCountStats {
        private final UUID id;
        private final String name;
        private int scenesCount;
        private int wordCount;

        NamedCountStats(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        void add(int sceneWordCount) {
            scenesCount++;
            wordCount += sceneWordCount;
        }

        void addScene() {
            scenesCount++;
        }
    }
}
