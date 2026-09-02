package com.iwrite.book.repository;

import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookRole;

/**
 * A Book the User may access in the active Workspace, together with the Book Role that grants that
 * access. Both are read by a single statement, so the relationship and the role a listing derives
 * always come from the same snapshot: a collaboration committed by another transaction is either
 * fully visible or not visible at all.
 *
 * @param role the accepted Book Role of a collaborator, or {@code null} when access comes from
 *             ownership, which is a relationship and never a role
 */
public record AccessibleBookWithRole(Book book, BookRole role) {
}
