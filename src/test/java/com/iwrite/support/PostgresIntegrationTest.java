package com.iwrite.support;

import com.iwrite.book.dto.BookRequest;
import com.iwrite.book.dto.BookResponse;
import com.iwrite.book.service.BookService;
import com.iwrite.chapter.dto.ChapterRequest;
import com.iwrite.chapter.dto.ChapterResponse;
import com.iwrite.chapter.service.ChapterService;
import com.iwrite.character.dto.CharacterRequest;
import com.iwrite.character.dto.CharacterResponse;
import com.iwrite.character.service.CharacterService;
import com.iwrite.item.dto.ItemRequest;
import com.iwrite.item.dto.ItemResponse;
import com.iwrite.item.service.ItemService;
import com.iwrite.location.dto.LocationRequest;
import com.iwrite.location.dto.LocationResponse;
import com.iwrite.location.service.LocationService;
import com.iwrite.scene.dto.SceneRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.scene.service.SceneService;
import com.iwrite.section.dto.BookSectionRequest;
import com.iwrite.section.dto.BookSectionResponse;
import com.iwrite.section.entity.SectionType;
import com.iwrite.section.service.BookSectionService;
import com.iwrite.writingprogress.dto.PersonalBookWritingGoalResponse;
import com.iwrite.writingprogress.dto.PersonalBookWritingGoalUpdateRequest;
import com.iwrite.writingprogress.service.PersonalBookWritingGoalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@SpringBootTest
@Transactional
public abstract class PostgresIntegrationTest {

    @DynamicPropertySource
    static void testDatasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabaseInitializer.prepareDatabase();
        registry.add("spring.datasource.url", TestDatabaseInitializer::testDbUrl);
        registry.add("spring.datasource.username", TestDatabaseInitializer::username);
        registry.add("spring.datasource.password", TestDatabaseInitializer::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("iwrite.current-user.development.enabled", () -> "true");
    }

    @Autowired
    protected BookService bookService;

    @Autowired
    protected BookSectionService sectionService;

    @Autowired
    protected ChapterService chapterService;

    @Autowired
    protected CharacterService characterService;

    @Autowired
    protected LocationService locationService;

    @Autowired
    protected ItemService itemService;

    @Autowired
    protected SceneService sceneService;

    @Autowired
    protected PersonalBookWritingGoalService personalBookWritingGoalService;

    protected BookResponse createBook(String title) {
        return bookService.create(new BookRequest(title, null, null, null, null));
    }

    protected BookResponse createBook(String title, Integer targetWordCount) {
        return bookService.create(new BookRequest(title, null, null, null, targetWordCount));
    }

    /** Sets the current User's own daily target; {@code null} clears it back to no chosen target. */
    protected PersonalBookWritingGoalResponse setPersonalDailyTarget(UUID bookId, Integer dailyTargetWordCount) {
        PersonalBookWritingGoalUpdateRequest request = new PersonalBookWritingGoalUpdateRequest();
        request.setDailyTargetWordCount(dailyTargetWordCount);
        return personalBookWritingGoalService.updateGoal(bookId, request);
    }

    /** Changes the current User's own planned writing days. */
    protected PersonalBookWritingGoalResponse setPersonalPlannedWritingDays(UUID bookId, List<DayOfWeek> plannedWritingDays) {
        PersonalBookWritingGoalUpdateRequest request = new PersonalBookWritingGoalUpdateRequest();
        request.setPlannedWritingDays(plannedWritingDays);
        return personalBookWritingGoalService.updateGoal(bookId, request);
    }

    protected BookSectionResponse createSection(BookResponse book, String title) {
        return sectionService.create(book.id(), new BookSectionRequest(title, SectionType.PART, 0));
    }

    protected ChapterResponse createChapter(BookSectionResponse section, String title) {
        return chapterService.create(section.id(), new ChapterRequest(title, null, 0));
    }

    protected SceneResponse createScene(ChapterResponse chapter, String title, SceneStatus status, int sortOrder, String contentText) {
        return sceneService.create(chapter.id(), new SceneRequest(title, null, status, sortOrder, "{\"type\":\"doc\"}", contentText));
    }

    protected CharacterResponse createCharacter(BookResponse book, String name) {
        return characterService.create(book.id(), new CharacterRequest(name, null, null, null, null, null, null, null, null, null, null, null));
    }

    protected LocationResponse createLocation(BookResponse book, String name) {
        return locationService.create(book.id(), new LocationRequest(name, null, null, null, null, null));
    }

    protected ItemResponse createItem(BookResponse book, String name) {
        return itemService.create(book.id(), new ItemRequest(name, null, null, null, null, null, null));
    }

    protected ItemResponse createItem(BookResponse book, String name, CharacterResponse owner) {
        return itemService.create(book.id(), new ItemRequest(name, null, null, null, owner.id(), null, null));
    }

    protected StoryWorld createStoryWorld(String bookTitle) {
        BookResponse book = createBook(bookTitle);
        BookSectionResponse section = createSection(book, "Part " + bookTitle);
        ChapterResponse chapter = createChapter(section, "Chapter " + bookTitle);
        CharacterResponse character = createCharacter(book, "Character " + bookTitle);
        LocationResponse location = createLocation(book, "Location " + bookTitle);
        ItemResponse item = createItem(book, "Item " + bookTitle, character);
        SceneResponse scene = createScene(chapter, "Scene " + bookTitle, SceneStatus.DRAFT, 0, "scene words");

        return new StoryWorld(book, section, chapter, scene, character, location, item);
    }

    protected String wordText(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(index -> "word" + index)
                .collect(Collectors.joining(" "));
    }

    protected record StoryWorld(
            BookResponse book,
            BookSectionResponse section,
            ChapterResponse chapter,
            SceneResponse scene,
            CharacterResponse character,
            LocationResponse location,
            ItemResponse item
    ) {
    }
}
