package com.iwrite.scene.dto;

import com.iwrite.book.authorization.BookAccessContext;
import com.iwrite.scene.authorization.SceneContentAuthority;
import com.iwrite.scene.entity.Scene;
import com.iwrite.scene.entity.SceneStatus;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A Scene as one User may currently see it.
 *
 * <p>{@code canEditContent} is the effective, resource-scoped authority over the canonical text of
 * this Scene, resolved by the same {@link SceneContentAuthority} the save applies (#207). It is not
 * the Book-scoped eligibility: {@code EDIT_AUTHORED_CONTRIBUTION} is contextual, so a consumer that
 * read eligibility as permission would offer an editor for a save the backend always refuses. The
 * projection lets a surface show only what it may attempt; the server still authorizes every request.
 */
public record SceneResponse(
        UUID id,
        UUID bookId,
        UUID chapterId,
        String title,
        String summary,
        String contentJson,
        String contentText,
        SceneStatus status,
        Integer sortOrder,
        Integer wordCount,
        Long contentRevision,
        boolean canEditContent,
        String goal,
        String conflict,
        String outcome,
        String planningNotes,
        CharacterSummaryResponse povCharacter,
        LocationSummaryResponse mainLocation,
        List<CharacterSummaryResponse> participantCharacters,
        List<ItemSummaryResponse> items,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    /**
     * Projects a Scene for the access that was proven to reach it. The access is required rather than
     * optional so a new call site cannot silently answer "not editable" to a User who may write.
     */
    public static SceneResponse fromEntity(Scene scene, BookAccessContext access) {
        return new SceneResponse(
                scene.getId(),
                scene.getBook().getId(),
                scene.getChapter().getId(),
                scene.getTitle(),
                scene.getSummary(),
                scene.getContentJson(),
                scene.getContentText(),
                scene.getStatus(),
                scene.getSortOrder(),
                scene.getWordCount(),
                scene.getContentRevision(),
                SceneContentAuthority.canEditSceneContent(access),
                scene.getGoal(),
                scene.getConflict(),
                scene.getOutcome(),
                scene.getPlanningNotes(),
                CharacterSummaryResponse.fromEntity(scene.getPovCharacter()),
                LocationSummaryResponse.fromEntity(scene.getMainLocation()),
                scene.getParticipantCharacters()
                        .stream()
                        .map(CharacterSummaryResponse::fromEntity)
                        .sorted(Comparator.comparing(CharacterSummaryResponse::name).thenComparing(CharacterSummaryResponse::id))
                        .toList(),
                scene.getItems()
                        .stream()
                        .map(ItemSummaryResponse::fromEntity)
                        .sorted(Comparator.comparing(ItemSummaryResponse::name).thenComparing(ItemSummaryResponse::id))
                        .toList(),
                scene.getCreatedAt(),
                scene.getUpdatedAt()
        );
    }
}
