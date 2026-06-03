package com.iwrite.search.controller;

import com.iwrite.search.dto.BookSearchResultResponse;
import com.iwrite.search.service.BookSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/books")
public class BookSearchController {

    private final BookSearchService searchService;

    public BookSearchController(BookSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/{bookId}/search")
    public List<BookSearchResultResponse> searchBook(
            @PathVariable UUID bookId,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "30") int limit
    ) {
        return searchService.search(bookId, q, limit);
    }
}
