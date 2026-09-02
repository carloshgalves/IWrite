package com.iwrite.book.repository;

import com.iwrite.book.entity.BookRole;

import java.util.UUID;

/** The accepted Book Role a User holds in one Book of the active Workspace. */
public record BookRoleAssignment(UUID bookId, BookRole role) {
}
