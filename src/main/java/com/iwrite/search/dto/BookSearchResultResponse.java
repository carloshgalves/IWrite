package com.iwrite.search.dto;

import java.util.UUID;

public record BookSearchResultResponse(
        UUID id,
        BookSearchResultType type,
        String title,
        String snippet,
        String metadata
) {
}
