package com.iwrite.book.authorization;

/**
 * Result of evaluating one {@link BookCapability} for one resolved access context.
 *
 * <p>The distinction between {@link #GRANTED} and {@link #CONTEXTUAL} is what keeps a Book-scoped
 * capability from being mistaken for authority over a specific resource.
 */
public enum BookCapabilityDecision {

    /** Book-scoped authorization is sufficient for the operation. */
    GRANTED,

    /**
     * Book-scoped eligibility only. The resource service must still evaluate its own predicate —
     * authorship of the affected Authored Contribution, availability of a Reader Review Release, and
     * so on — before performing the operation.
     */
    CONTEXTUAL,

    /** Explicit negation. The operation is never available to this context in this partition. */
    DENIED
}
