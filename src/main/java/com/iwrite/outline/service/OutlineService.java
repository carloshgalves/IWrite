package com.iwrite.outline.service;

import com.iwrite.book.authorization.BookCapability;
import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookAccessService;
import com.iwrite.chapter.entity.Chapter;
import com.iwrite.chapter.repository.ChapterRepository;
import com.iwrite.outline.dto.BookOutlineResponse;
import com.iwrite.outline.dto.OutlineChapterResponse;
import com.iwrite.outline.dto.OutlineSceneResponse;
import com.iwrite.outline.dto.OutlineSectionResponse;
import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.scene.service.ScenePlanningCompletenessService;
import com.iwrite.section.entity.BookSection;
import com.iwrite.section.repository.BookSectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The Book outline: the whole manuscript hierarchy of a Book in one read.
 *
 * <p>The outline is a projection of the current Manuscript, so it requires the same
 * {@code READ_MANUSCRIPT} capability as the Scenes it lists (#207). A Reader holds no capability over
 * the living Manuscript and gets the same non-enumerable answer as someone with no relationship to the
 * Book at all; a Reader's access to released material is a separate surface (#148).
 */
@Service
public class OutlineService {

    private final BookAccessService bookAccessService;
    private final BookSectionRepository sectionRepository;
    private final ChapterRepository chapterRepository;
    private final SceneRepository sceneRepository;
    private final ScenePlanningCompletenessService planningCompletenessService;

    public OutlineService(
            BookAccessService bookAccessService,
            BookSectionRepository sectionRepository,
            ChapterRepository chapterRepository,
            SceneRepository sceneRepository,
            ScenePlanningCompletenessService planningCompletenessService
    ) {
        this.bookAccessService = bookAccessService;
        this.sectionRepository = sectionRepository;
        this.chapterRepository = chapterRepository;
        this.sceneRepository = sceneRepository;
        this.planningCompletenessService = planningCompletenessService;
    }

    @Transactional(readOnly = true)
    public BookOutlineResponse getOutline(UUID bookId) {
        Book book = bookAccessService.requireCapability(bookId, BookCapability.READ_MANUSCRIPT);
        List<BookSection> sections = sectionRepository.findByBookIdOrderBySortOrderAsc(bookId);
        List<Chapter> chapters = chapterRepository.findByBookIdOrderBySortOrderAsc(bookId);
        List<Scene> scenes = sceneRepository.findOutlineScenesByBookId(bookId);

        Map<UUID, List<Scene>> scenesByChapter = scenes.stream()
                .collect(Collectors.groupingBy(scene -> scene.getChapter().getId()));

        Map<UUID, List<Chapter>> chaptersBySection = chapters.stream()
                .collect(Collectors.groupingBy(chapter -> chapter.getSection().getId()));

        List<OutlineSectionResponse> sectionResponses = sections.stream()
                .map(section -> mapSection(section, chaptersBySection.getOrDefault(section.getId(), List.of()), scenesByChapter))
                .toList();

        int bookWordCount = sectionResponses.stream()
                .mapToInt(OutlineSectionResponse::wordCount)
                .sum();

        return new BookOutlineResponse(
                book.getId(),
                book.getTitle(),
                book.getStatus(),
                bookWordCount,
                sectionResponses
        );
    }

    private OutlineSectionResponse mapSection(
            BookSection section,
            List<Chapter> chapters,
            Map<UUID, List<Scene>> scenesByChapter
    ) {
        List<OutlineChapterResponse> chapterResponses = chapters.stream()
                .map(chapter -> mapChapter(chapter, scenesByChapter.getOrDefault(chapter.getId(), List.of())))
                .toList();

        int sectionWordCount = chapterResponses.stream()
                .mapToInt(OutlineChapterResponse::wordCount)
                .sum();

        return new OutlineSectionResponse(
                section.getId(),
                section.getTitle(),
                section.getType(),
                section.getSortOrder(),
                sectionWordCount,
                chapterResponses
        );
    }

    private OutlineChapterResponse mapChapter(Chapter chapter, List<Scene> scenes) {
        List<OutlineSceneResponse> sceneResponses = scenes.stream()
                .map(scene -> new OutlineSceneResponse(
                        scene.getId(),
                        scene.getTitle(),
                        scene.getStatus(),
                        scene.getSortOrder(),
                        scene.getWordCount(),
                        scene.getPovCharacter() == null ? null : scene.getPovCharacter().getId(),
                        scene.getPovCharacter() == null ? null : scene.getPovCharacter().getName(),
                        planningCompletenessService.planningGaps(scene)
                ))
                .toList();

        int chapterWordCount = sceneResponses.stream()
                .mapToInt(OutlineSceneResponse::wordCount)
                .sum();

        return new OutlineChapterResponse(
                chapter.getId(),
                chapter.getTitle(),
                chapter.getSummary(),
                chapter.getSortOrder(),
                chapterWordCount,
                sceneResponses
        );
    }
}
