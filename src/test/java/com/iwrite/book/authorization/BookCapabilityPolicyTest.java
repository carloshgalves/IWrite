package com.iwrite.book.authorization;

import com.iwrite.book.entity.BookRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static com.iwrite.book.authorization.BookCapability.CREATE_EDITORIAL_COMMENT;
import static com.iwrite.book.authorization.BookCapability.CREATE_EDITORIAL_SUGGESTION;
import static com.iwrite.book.authorization.BookCapability.DELETE_BOOK;
import static com.iwrite.book.authorization.BookCapability.EDIT_AUTHORED_CONTRIBUTION;
import static com.iwrite.book.authorization.BookCapability.EDIT_BOOK_SETTINGS;
import static com.iwrite.book.authorization.BookCapability.EDIT_CANONICAL_PLANNING;
import static com.iwrite.book.authorization.BookCapability.EDIT_NOTEBOOK;
import static com.iwrite.book.authorization.BookCapability.EXPORT_MANUSCRIPT;
import static com.iwrite.book.authorization.BookCapability.EXPORT_NOTEBOOK;
import static com.iwrite.book.authorization.BookCapability.MANAGE_COLLABORATORS;
import static com.iwrite.book.authorization.BookCapability.MUTATE_MANUSCRIPT_STRUCTURE;
import static com.iwrite.book.authorization.BookCapability.READ_CANONICAL_PLANNING;
import static com.iwrite.book.authorization.BookCapability.READ_MANUSCRIPT;
import static com.iwrite.book.authorization.BookCapability.READ_NOTEBOOK;
import static com.iwrite.book.authorization.BookCapability.READ_READER_REVIEW_RELEASE;
import static com.iwrite.book.authorization.BookCapability.READ_SCENE_VERSIONS;
import static com.iwrite.book.authorization.BookCapability.RESOLVE_EDITORIAL_SUGGESTION;
import static com.iwrite.book.authorization.BookCapability.RESTORE_SCENE_VERSION;
import static com.iwrite.book.authorization.BookCapability.REQUEST_SCENE_AI_ANALYSIS;
import static com.iwrite.book.authorization.BookCapability.VIEW_BOOK_CONTRIBUTOR_PROGRESS;
import static com.iwrite.book.authorization.BookCapabilityDecision.CONTEXTUAL;
import static com.iwrite.book.authorization.BookCapabilityDecision.DENIED;
import static com.iwrite.book.authorization.BookCapabilityDecision.GRANTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the initial capability matrix of #145 for the Book Owner and every persistable Book Role,
 * including the explicit negations and the distinction between a Book-scoped grant and eligibility
 * that still needs a resource-scoped predicate.
 */
class BookCapabilityPolicyTest {

    private final BookCapabilityPolicy policy = new BookCapabilityPolicy();

    static Stream<Arguments> matrix() {
        return Stream.of(
                //     capability                       owner       AUTHOR      EDITOR   READER      LEGACY
                row(READ_MANUSCRIPT, GRANTED, GRANTED, GRANTED, DENIED, GRANTED),
                row(EDIT_AUTHORED_CONTRIBUTION, CONTEXTUAL, CONTEXTUAL, DENIED, DENIED, GRANTED),
                row(MUTATE_MANUSCRIPT_STRUCTURE, GRANTED, DENIED, DENIED, DENIED, GRANTED),
                row(READ_CANONICAL_PLANNING, GRANTED, GRANTED, GRANTED, DENIED, GRANTED),
                row(EDIT_CANONICAL_PLANNING, GRANTED, GRANTED, DENIED, DENIED, GRANTED),
                row(READ_NOTEBOOK, GRANTED, GRANTED, GRANTED, DENIED, GRANTED),
                row(EDIT_NOTEBOOK, GRANTED, GRANTED, DENIED, DENIED, GRANTED),
                row(VIEW_BOOK_CONTRIBUTOR_PROGRESS, GRANTED, GRANTED, GRANTED, DENIED, GRANTED),
                row(BookCapability.MANAGE_OWN_PERSONAL_WRITING_GOAL, GRANTED, GRANTED, DENIED, DENIED, GRANTED),
                row(EDIT_BOOK_SETTINGS, GRANTED, DENIED, DENIED, DENIED, GRANTED),
                row(CREATE_EDITORIAL_COMMENT, GRANTED, GRANTED, GRANTED, CONTEXTUAL, DENIED),
                row(CREATE_EDITORIAL_SUGGESTION, GRANTED, GRANTED, GRANTED, DENIED, DENIED),
                row(RESOLVE_EDITORIAL_SUGGESTION, CONTEXTUAL, CONTEXTUAL, DENIED, DENIED, DENIED),
                row(READ_SCENE_VERSIONS, GRANTED, GRANTED, GRANTED, DENIED, GRANTED),
                row(RESTORE_SCENE_VERSION, GRANTED, DENIED, DENIED, DENIED, GRANTED),
                row(EXPORT_MANUSCRIPT, GRANTED, GRANTED, DENIED, DENIED, GRANTED),
                row(EXPORT_NOTEBOOK, GRANTED, GRANTED, DENIED, DENIED, GRANTED),
                row(REQUEST_SCENE_AI_ANALYSIS, GRANTED, GRANTED, GRANTED, DENIED, GRANTED),
                row(READ_READER_REVIEW_RELEASE, DENIED, DENIED, DENIED, CONTEXTUAL, DENIED),
                row(MANAGE_COLLABORATORS, GRANTED, DENIED, DENIED, DENIED, DENIED),
                row(DELETE_BOOK, GRANTED, DENIED, DENIED, DENIED, DENIED)
        ).flatMap(BookCapabilityPolicyTest::expand);
    }

    @ParameterizedTest(name = "{0} for {1}/{2} is {3}")
    @MethodSource("matrix")
    void decidesTheInitialCapabilityMatrix(
            BookCapability capability,
            BookRelationship relationship,
            BookRole role,
            BookCapabilityDecision expected
    ) {
        assertThat(policy.decide(relationship, role, capability)).isEqualTo(expected);
    }

    @Test
    void everyCapabilityOfTheCatalogIsDecidedForEverySubject() {
        assertThat(matrix().count())
                .isEqualTo((long) BookCapability.values().length * (BookRole.values().length + 1));
    }

    @Test
    void bookOwnershipAloneIsNotAuthorityOverAnotherAuthorsContribution() {
        assertThat(policy.decide(BookRelationship.OWNER, null, EDIT_AUTHORED_CONTRIBUTION)).isEqualTo(CONTEXTUAL);
        assertThat(policy.decide(BookRelationship.OWNER, null, RESOLVE_EDITORIAL_SUGGESTION)).isEqualTo(CONTEXTUAL);
        assertThat(policy.grantedCapabilities(BookRelationship.OWNER, null))
                .doesNotContain(EDIT_AUTHORED_CONTRIBUTION, RESOLVE_EDITORIAL_SUGGESTION);
        assertThat(policy.contextualCapabilities(BookRelationship.OWNER, null))
                .containsExactlyInAnyOrder(EDIT_AUTHORED_CONTRIBUTION, RESOLVE_EDITORIAL_SUGGESTION);
    }

    @Test
    void authorDoesNotReceiveBroadEditingAsAShortcut() {
        assertThat(policy.grantedCapabilities(BookRelationship.COLLABORATOR, BookRole.AUTHOR))
                .doesNotContain(
                        EDIT_AUTHORED_CONTRIBUTION,
                        MUTATE_MANUSCRIPT_STRUCTURE,
                        EDIT_BOOK_SETTINGS,
                        RESTORE_SCENE_VERSION,
                        MANAGE_COLLABORATORS,
                        DELETE_BOOK
                );
    }

    @Test
    void editorNeverMutatesCanonicalMaterial() {
        assertThat(policy.grantedCapabilities(BookRelationship.COLLABORATOR, BookRole.EDITOR))
                .contains(READ_MANUSCRIPT, READ_CANONICAL_PLANNING, READ_NOTEBOOK, REQUEST_SCENE_AI_ANALYSIS)
                .doesNotContain(
                        EDIT_AUTHORED_CONTRIBUTION,
                        EDIT_CANONICAL_PLANNING,
                        EDIT_NOTEBOOK,
                        MUTATE_MANUSCRIPT_STRUCTURE,
                        RESTORE_SCENE_VERSION,
                        EXPORT_MANUSCRIPT,
                        EXPORT_NOTEBOOK
                );
        assertThat(policy.contextualCapabilities(BookRelationship.COLLABORATOR, BookRole.EDITOR)).isEmpty();
    }

    @Test
    void readerIsOnlyEligibleForReleasedMaterialAndFeedbackOnIt() {
        assertThat(policy.grantedCapabilities(BookRelationship.COLLABORATOR, BookRole.READER)).isEmpty();
        assertThat(policy.contextualCapabilities(BookRelationship.COLLABORATOR, BookRole.READER))
                .containsExactlyInAnyOrder(READ_READER_REVIEW_RELEASE, CREATE_EDITORIAL_COMMENT);
    }

    @Test
    void legacyCollaboratorKeepsThePreviousSurfaceWithoutAcquiringFutureCapabilities() {
        assertThat(policy.grantedCapabilities(BookRelationship.COLLABORATOR, BookRole.LEGACY_COLLABORATOR))
                .containsExactlyInAnyOrder(
                        READ_MANUSCRIPT,
                        EDIT_AUTHORED_CONTRIBUTION,
                        MUTATE_MANUSCRIPT_STRUCTURE,
                        READ_CANONICAL_PLANNING,
                        EDIT_CANONICAL_PLANNING,
                        READ_NOTEBOOK,
                        EDIT_NOTEBOOK,
                        VIEW_BOOK_CONTRIBUTOR_PROGRESS,
                        BookCapability.MANAGE_OWN_PERSONAL_WRITING_GOAL,
                        EDIT_BOOK_SETTINGS,
                        READ_SCENE_VERSIONS,
                        RESTORE_SCENE_VERSION,
                        EXPORT_MANUSCRIPT,
                        EXPORT_NOTEBOOK,
                        REQUEST_SCENE_AI_ANALYSIS
                );
        assertThat(policy.contextualCapabilities(BookRelationship.COLLABORATOR, BookRole.LEGACY_COLLABORATOR)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(BookRole.class)
    void onlyTheOwnerAdministersCollaboratorsAndDeletesTheBook(BookRole role) {
        assertThat(policy.decide(BookRelationship.COLLABORATOR, role, MANAGE_COLLABORATORS)).isEqualTo(DENIED);
        assertThat(policy.decide(BookRelationship.COLLABORATOR, role, DELETE_BOOK)).isEqualTo(DENIED);
    }

    @Test
    void ownershipAndRoleAreDistinctInputsAndCannotBeSubstituted() {
        assertThatThrownBy(() -> policy.decide(BookRelationship.OWNER, BookRole.AUTHOR, READ_MANUSCRIPT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.decide(BookRelationship.COLLABORATOR, null, READ_MANUSCRIPT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyProductRolesAreAssignable() {
        assertThat(Arrays.stream(BookRole.values()).filter(BookRole::isAssignable).toList())
                .containsExactlyInAnyOrder(BookRole.AUTHOR, BookRole.EDITOR, BookRole.READER);
        assertThat(BookRole.LEGACY_COLLABORATOR.isAssignable()).isFalse();
    }

    private static Arguments row(
            BookCapability capability,
            BookCapabilityDecision owner,
            BookCapabilityDecision author,
            BookCapabilityDecision editor,
            BookCapabilityDecision reader,
            BookCapabilityDecision legacyCollaborator
    ) {
        return Arguments.of(capability, List.of(owner, author, editor, reader, legacyCollaborator));
    }

    @SuppressWarnings("unchecked")
    private static Stream<Arguments> expand(Arguments row) {
        BookCapability capability = (BookCapability) row.get()[0];
        List<BookCapabilityDecision> decisions = (List<BookCapabilityDecision>) row.get()[1];
        return Stream.of(
                Arguments.of(capability, BookRelationship.OWNER, null, decisions.get(0)),
                Arguments.of(capability, BookRelationship.COLLABORATOR, BookRole.AUTHOR, decisions.get(1)),
                Arguments.of(capability, BookRelationship.COLLABORATOR, BookRole.EDITOR, decisions.get(2)),
                Arguments.of(capability, BookRelationship.COLLABORATOR, BookRole.READER, decisions.get(3)),
                Arguments.of(capability, BookRelationship.COLLABORATOR, BookRole.LEGACY_COLLABORATOR, decisions.get(4))
        );
    }
}
