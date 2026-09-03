package com.iwrite.book.authorization;

/**
 * How a User is related to a Book, as resolved by the backend.
 *
 * <p>The relationship answers "through what", never "with which authority": a COLLABORATOR always
 * carries a {@link com.iwrite.book.entity.BookRole}, and the Owner relationship is the explicit
 * {@code books.owner_user_id} link rather than a collaboration row or a role a User can choose.
 */
public enum BookRelationship {
    OWNER,
    COLLABORATOR
}
