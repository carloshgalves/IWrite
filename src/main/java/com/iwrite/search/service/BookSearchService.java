package com.iwrite.search.service;

import com.iwrite.book.service.BookService;
import com.iwrite.character.entity.Character;
import com.iwrite.character.repository.CharacterRepository;
import com.iwrite.item.entity.Item;
import com.iwrite.item.repository.ItemRepository;
import com.iwrite.location.entity.Location;
import com.iwrite.location.repository.LocationRepository;
import com.iwrite.notebook.entity.NotebookNote;
import com.iwrite.notebook.repository.NotebookNoteRepository;
import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.search.dto.BookSearchResultResponse;
import com.iwrite.search.dto.BookSearchResultType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class BookSearchService {

    private static final int MIN_QUERY_LENGTH = 2;
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 50;
    private static final int SNIPPET_RADIUS = 55;
    private static final int SNIPPET_MAX_LENGTH = 140;

    private final BookService bookService;
    private final SceneRepository sceneRepository;
    private final NotebookNoteRepository notebookNoteRepository;
    private final CharacterRepository characterRepository;
    private final LocationRepository locationRepository;
    private final ItemRepository itemRepository;

    public BookSearchService(
            BookService bookService,
            SceneRepository sceneRepository,
            NotebookNoteRepository notebookNoteRepository,
            CharacterRepository characterRepository,
            LocationRepository locationRepository,
            ItemRepository itemRepository
    ) {
        this.bookService = bookService;
        this.sceneRepository = sceneRepository;
        this.notebookNoteRepository = notebookNoteRepository;
        this.characterRepository = characterRepository;
        this.locationRepository = locationRepository;
        this.itemRepository = itemRepository;
    }

    @Transactional(readOnly = true)
    public List<BookSearchResultResponse> search(UUID bookId, String query, int requestedLimit) {
        bookService.getBook(bookId);

        String trimmedQuery = query == null ? "" : query.trim();
        if (trimmedQuery.length() < MIN_QUERY_LENGTH) {
            return List.of();
        }

        int limit = normalizeLimit(requestedLimit);
        String pattern = "%" + escapeIlikePattern(trimmedQuery) + "%";
        List<BookSearchResultResponse> results = new ArrayList<>();

        for (Scene scene : sceneRepository.searchBookScenes(bookId, pattern, limit)) {
            results.add(new BookSearchResultResponse(
                    scene.getId(),
                    BookSearchResultType.SCENE,
                    scene.getTitle(),
                    snippet(trimmedQuery, scene.getTitle(), scene.getSummary(), scene.getContentText()),
                    scene.getChapter().getSection().getTitle() + " - " + scene.getChapter().getTitle()
            ));
        }

        for (NotebookNote note : notebookNoteRepository.searchBookNotes(bookId, pattern, limit)) {
            String metadata = note.getCategory() == null ? statusLabel(note) : note.getCategory().getName() + " - " + statusLabel(note);
            results.add(new BookSearchResultResponse(
                    note.getId(),
                    BookSearchResultType.NOTEBOOK_NOTE,
                    note.getTitle(),
                    snippet(trimmedQuery, note.getTitle(), note.getContent()),
                    metadata
            ));
        }

        for (Character character : characterRepository.searchBookCharacters(bookId, pattern, limit)) {
            results.add(new BookSearchResultResponse(
                    character.getId(),
                    BookSearchResultType.CHARACTER,
                    character.getName(),
                    snippet(
                            trimmedQuery,
                            character.getName(),
                            character.getNickname(),
                            character.getBiography(),
                            character.getNotes(),
                            character.getNarrativeFunction(),
                            character.getGoal(),
                            character.getConflict(),
                            character.getArc(),
                            character.getPhysicalDescription(),
                            character.getPersonality()
                    ),
                    "Personagem"
            ));
        }

        for (Location location : locationRepository.searchBookLocations(bookId, pattern, limit)) {
            results.add(new BookSearchResultResponse(
                    location.getId(),
                    BookSearchResultType.LOCATION,
                    location.getName(),
                    snippet(
                            trimmedQuery,
                            location.getName(),
                            location.getType(),
                            location.getDescription(),
                            location.getHistoryContext(),
                            location.getNarrativeImportance(),
                            location.getNotes()
                    ),
                    location.getType() == null || location.getType().isBlank() ? "Localização" : location.getType()
            ));
        }

        for (Item item : itemRepository.searchBookItems(bookId, pattern, limit)) {
            String metadata = item.getType() == null || item.getType().isBlank() ? "Item" : item.getType();
            if (item.getCurrentOwnerCharacter() != null) {
                metadata = metadata + " - " + item.getCurrentOwnerCharacter().getName();
            }
            results.add(new BookSearchResultResponse(
                    item.getId(),
                    BookSearchResultType.ITEM,
                    item.getName(),
                    snippet(
                            trimmedQuery,
                            item.getName(),
                            item.getType(),
                            item.getDescription(),
                            item.getOrigin(),
                            item.getNarrativeImportance(),
                            item.getNotes(),
                            item.getCurrentOwnerCharacter() == null ? null : item.getCurrentOwnerCharacter().getName()
                    ),
                    metadata
            ));
        }

        return results.stream().limit(limit).toList();
    }

    private int normalizeLimit(int requestedLimit) {
        if (requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private String escapeIlikePattern(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private String snippet(String query, String... values) {
        for (String value : values) {
            String normalizedValue = normalizeWhitespace(value);
            if (normalizedValue == null) {
                continue;
            }

            int matchIndex = normalizedValue.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
            if (matchIndex >= 0) {
                return trimSnippet(normalizedValue, matchIndex);
            }
        }
        return null;
    }

    private String normalizeWhitespace(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private String trimSnippet(String value, int matchIndex) {
        int start = Math.max(0, matchIndex - SNIPPET_RADIUS);
        int end = Math.min(value.length(), matchIndex + SNIPPET_RADIUS);
        String prefix = start > 0 ? "..." : "";
        String suffix = end < value.length() ? "..." : "";
        String snippet = prefix + value.substring(start, end).trim() + suffix;

        if (snippet.length() <= SNIPPET_MAX_LENGTH) {
            return snippet;
        }
        return snippet.substring(0, SNIPPET_MAX_LENGTH - 3).trim() + "...";
    }

    private String statusLabel(NotebookNote note) {
        return note.getStatus().name();
    }
}
