package com.iwrite.search;

import com.iwrite.character.dto.CharacterRequest;
import com.iwrite.item.dto.ItemRequest;
import com.iwrite.location.dto.LocationRequest;
import com.iwrite.notebook.dto.NotebookCategoryRequest;
import com.iwrite.notebook.dto.NotebookNoteRequest;
import com.iwrite.notebook.entity.NotebookNoteStatus;
import com.iwrite.notebook.service.NotebookService;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BookSearchControllerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotebookService notebookService;

    @Test
    void searchesAllBookContentDomains() throws Exception {
        var book = createBook("Search domains");
        var section = createSection(book, "Part Aurora");
        var chapter = createChapter(section, "Chapter Aurora");
        createScene(chapter, "Scene title", SceneStatus.DRAFT, 0, "The hidden aurora appears in scene content.");
        var category = notebookService.createCategory(book.id(), new NotebookCategoryRequest("Research", 20));
        notebookService.createNote(book.id(), new NotebookNoteRequest(
                "Notebook title",
                "Notebook content mentions aurora.",
                category.id(),
                NotebookNoteStatus.OPEN
        ));
        characterService.create(book.id(), new CharacterRequest(
                "Hero",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "Biography follows the aurora clue.",
                null
        ));
        locationService.create(book.id(), new LocationRequest(
                "Harbor",
                null,
                "Location description keeps the aurora clue.",
                null,
                null,
                null
        ));
        itemService.create(book.id(), new ItemRequest(
                "Compass",
                null,
                "Item description points toward aurora.",
                null,
                null,
                null,
                null
        ));

        mockMvc.perform(get("/api/books/{bookId}/search", book.id()).param("q", "aurora"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[*].type", hasItem("SCENE")))
                .andExpect(jsonPath("$[*].type", hasItem("NOTEBOOK_NOTE")))
                .andExpect(jsonPath("$[*].type", hasItem("CHARACTER")))
                .andExpect(jsonPath("$[*].type", hasItem("LOCATION")))
                .andExpect(jsonPath("$[*].type", hasItem("ITEM")))
                .andExpect(jsonPath("$[0].snippet", containsString("aurora")))
                .andExpect(jsonPath("$[0].navigation").doesNotExist())
                .andExpect(jsonPath("$[0].contentText").doesNotExist());
    }

    @Test
    void searchesNotebookNotesByTitleAndContent() throws Exception {
        var book = createBook("Search notebook");
        notebookService.createNote(book.id(), new NotebookNoteRequest(
                "Archive clue",
                "No match here.",
                null,
                NotebookNoteStatus.OPEN
        ));
        notebookService.createNote(book.id(), new NotebookNoteRequest(
                "General note",
                "The archive appears in the note body.",
                null,
                NotebookNoteStatus.RESOLVED
        ));

        mockMvc.perform(get("/api/books/{bookId}/search", book.id()).param("q", "archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].type", hasItem("NOTEBOOK_NOTE")))
                .andExpect(jsonPath("$[*].metadata", hasItem("OPEN")))
                .andExpect(jsonPath("$[*].metadata", hasItem("RESOLVED")));
    }

    @Test
    void escapesIlikeWildcards() throws Exception {
        var book = createBook("Search wildcards");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        createScene(chapter, "Literal 100%_match", SceneStatus.DRAFT, 0, "literal only");
        createScene(chapter, "Wildcard 100ZZmatch", SceneStatus.DRAFT, 1, "would match if wildcards leaked");

        mockMvc.perform(get("/api/books/{bookId}/search", book.id()).param("q", "%_"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("Literal 100%_match"));
    }

    @Test
    void trimsQueryAndIgnoresShortQueries() throws Exception {
        var book = createBook("Search trim");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        createScene(chapter, "Trimmed token scene", SceneStatus.DRAFT, 0, "body");

        mockMvc.perform(get("/api/books/{bookId}/search", book.id()).param("q", "  token  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("Trimmed token scene"));

        mockMvc.perform(get("/api/books/{bookId}/search", book.id()).param("q", " t "))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void keepsResultsIsolatedToBook() throws Exception {
        var firstBook = createBook("Search isolation first");
        var firstSection = createSection(firstBook, "Part");
        var firstChapter = createChapter(firstSection, "Chapter");
        createScene(firstChapter, "Shared needle first", SceneStatus.DRAFT, 0, "body");

        var secondBook = createBook("Search isolation second");
        var secondSection = createSection(secondBook, "Part");
        var secondChapter = createChapter(secondSection, "Chapter");
        createScene(secondChapter, "Shared needle second", SceneStatus.DRAFT, 0, "body");

        mockMvc.perform(get("/api/books/{bookId}/search", firstBook.id()).param("q", "needle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("Shared needle first"));
    }

    @Test
    void returnsNotFoundForMissingBook() throws Exception {
        mockMvc.perform(get("/api/books/{bookId}/search", UUID.randomUUID()).param("q", "needle"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.messages", hasItem(containsString("Book not found"))));
    }

    @Test
    void appliesCombinedResultLimit() throws Exception {
        var book = createBook("Search limit");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        createScene(chapter, "Limit needle one", SceneStatus.DRAFT, 0, "body");
        createScene(chapter, "Limit needle two", SceneStatus.DRAFT, 1, "body");
        createScene(chapter, "Limit needle three", SceneStatus.DRAFT, 2, "body");

        mockMvc.perform(get("/api/books/{bookId}/search", book.id()).param("q", "needle").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void responseContainsOnlySearchResultFields() throws Exception {
        var book = createBook("Search shape");
        var section = createSection(book, "Part");
        var chapter = createChapter(section, "Chapter");
        createScene(chapter, "Shape needle", SceneStatus.DRAFT, 0, "body");

        mockMvc.perform(get("/api/books/{bookId}/search", book.id()).param("q", "needle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].type").value("SCENE"))
                .andExpect(jsonPath("$[0].title").value("Shape needle"))
                .andExpect(jsonPath("$[0].snippet").exists())
                .andExpect(jsonPath("$[0].metadata").exists())
                .andExpect(jsonPath("$[0].bookId").doesNotExist())
                .andExpect(jsonPath("$[0].navigation").doesNotExist())
                .andExpect(jsonPath("$[0].contentJson").doesNotExist())
                .andExpect(jsonPath("$[0].contentText").doesNotExist())
                .andExpect(content().string(not(containsString("workspaceTab"))));
    }
}
