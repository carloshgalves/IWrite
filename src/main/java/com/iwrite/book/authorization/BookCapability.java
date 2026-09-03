package com.iwrite.book.authorization;

/**
 * Closed catalog of Book-scoped operations that authorization is expressed in terms of (#145).
 *
 * <p>Guards require the minimum capability for the operation instead of a nominal role check.
 * Capabilities describe Book-scoped eligibility only; an operation whose domain also depends on a
 * resource-scoped predicate — authority over an Authored Contribution, access to a specific Reader
 * Review Release, a valid AI Assistant Entitlement — still evaluates that predicate in the resource
 * service. See {@link BookCapabilityDecision#CONTEXTUAL}.
 */
public enum BookCapability {

    READ_MANUSCRIPT,
    EDIT_AUTHORED_CONTRIBUTION,
    MUTATE_MANUSCRIPT_STRUCTURE,
    READ_CANONICAL_PLANNING,
    EDIT_CANONICAL_PLANNING,
    READ_NOTEBOOK,
    EDIT_NOTEBOOK,
    VIEW_BOOK_CONTRIBUTOR_PROGRESS,
    MANAGE_OWN_PERSONAL_WRITING_GOAL,
    EDIT_BOOK_SETTINGS,
    CREATE_EDITORIAL_COMMENT,
    CREATE_EDITORIAL_SUGGESTION,
    RESOLVE_EDITORIAL_SUGGESTION,
    READ_SCENE_VERSIONS,
    RESTORE_SCENE_VERSION,
    EXPORT_MANUSCRIPT,
    EXPORT_NOTEBOOK,
    REQUEST_SCENE_AI_ANALYSIS,
    READ_READER_REVIEW_RELEASE,
    MANAGE_COLLABORATORS,
    DELETE_BOOK
}
