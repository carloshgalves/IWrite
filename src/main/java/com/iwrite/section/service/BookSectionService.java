package com.iwrite.section.service;

import com.iwrite.book.authorization.BookCapability;
import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookAccessService;
import com.iwrite.common.dto.ReorderRequest;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.common.validation.RequestValidation;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.scene.service.SceneDeletionLedgerService;
import com.iwrite.section.dto.BookSectionRequest;
import com.iwrite.section.dto.BookSectionResponse;
import com.iwrite.section.dto.BookSectionUpdateRequest;
import com.iwrite.section.entity.BookSection;
import com.iwrite.section.entity.SectionType;
import com.iwrite.section.repository.BookSectionRepository;
import com.iwrite.user.context.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Book Section surfaces of the manuscript hierarchy.
 *
 * <p>Reading a Section is part of reading the Manuscript; creating, renaming, reordering, moving or
 * deleting one is a Manuscript Structure Mutation, which the Book Capability Policy authorizes for the
 * Book Owner alone in this partition (#207). The two lookups below exist so a caller has to name which
 * of the two it is doing instead of inheriting a generic "can edit this Book" answer.
 */
@Service
public class BookSectionService {

    private final BookSectionRepository sectionRepository;
    private final BookAccessService bookAccessService;
    private final SceneRepository sceneRepository;
    private final SceneDeletionLedgerService sceneDeletionLedgerService;
    private final CurrentUserProvider currentUserProvider;

    public BookSectionService(
            BookSectionRepository sectionRepository,
            BookAccessService bookAccessService,
            SceneRepository sceneRepository,
            SceneDeletionLedgerService sceneDeletionLedgerService,
            CurrentUserProvider currentUserProvider
    ) {
        this.sectionRepository = sectionRepository;
        this.bookAccessService = bookAccessService;
        this.sceneRepository = sceneRepository;
        this.sceneDeletionLedgerService = sceneDeletionLedgerService;
        this.currentUserProvider = currentUserProvider;
    }

    @Transactional
    public BookSectionResponse create(UUID bookId, BookSectionRequest request) {
        Book book = bookAccessService.requireCapabilityForUpdate(bookId, BookCapability.MUTATE_MANUSCRIPT_STRUCTURE);

        BookSection section = new BookSection();
        section.setBook(book);
        section.setTitle(request.title());
        section.setType(request.type() == null ? SectionType.PART : request.type());
        section.setSortOrder(request.sortOrder() == null ? sectionRepository.countByBookId(bookId) : request.sortOrder());

        return BookSectionResponse.fromEntity(sectionRepository.save(section));
    }

    @Transactional
    public BookSectionResponse update(UUID sectionId, BookSectionUpdateRequest request) {
        BookSection section = getSectionForStructureMutationUnderLock(sectionId);
        RequestValidation.rejectBlankWhenPresent("title", request.title());

        if (request.title() != null) {
            section.setTitle(request.title());
        }
        if (request.type() != null) {
            section.setType(request.type());
        }
        if (request.sortOrder() != null) {
            section.setSortOrder(request.sortOrder());
        }

        return BookSectionResponse.fromEntity(section);
    }

    @Transactional
    public void delete(UUID sectionId) {
        BookSection section = getSectionForStructureMutation(sectionId);
        var scenes = sceneRepository.findBySectionIdForUpdate(sectionId);
        Book lockedBook = bookAccessService.requireCapabilityForUpdate(
                section.getBook().getId(),
                BookCapability.MUTATE_MANUSCRIPT_STRUCTURE
        );
        sceneDeletionLedgerService.prepareSceneDeletes(scenes, lockedBook, UUID.randomUUID());
        sectionRepository.deleteById(sectionId);
    }

    @Transactional
    public void reorder(UUID bookId, ReorderRequest request) {
        bookAccessService.requireCapabilityForUpdate(bookId, BookCapability.MUTATE_MANUSCRIPT_STRUCTURE);
        List<BookSection> sections = sectionRepository.findByBookIdOrderBySortOrderAsc(bookId);
        applyReorder(sections, request.orderedIds(), BookSection::getId, BookSection::setSortOrder, "sections");
    }

    /** Reads a Section the current User may read the Manuscript of. */
    @Transactional(readOnly = true)
    public BookSection getSection(UUID sectionId) {
        return requireSection(sectionId, BookCapability.READ_MANUSCRIPT);
    }

    /** Reads a Section the current User may restructure, for a Manuscript Structure Mutation. */
    @Transactional(readOnly = true)
    public BookSection getSectionForStructureMutation(UUID sectionId) {
        return requireSection(sectionId, BookCapability.MUTATE_MANUSCRIPT_STRUCTURE);
    }

    /**
     * Same proof as {@link #getSectionForStructureMutation(UUID)}, re-taken under the Book row lock,
     * for a caller that is about to write.
     *
     * <p>The read-only variant answers "may this User restructure right now", which a mutation cannot
     * rely on: a revocation committing between that answer and the write would be straddled, and the
     * Manuscript would be restructured on authority that no longer exists. Locking the Book row and
     * proving the capability again under it is the same discipline the canonical content save uses.
     */
    @Transactional
    public BookSection getSectionForStructureMutationUnderLock(UUID sectionId) {
        BookSection section = sectionRepository.findByIdAndBook_Tenant_Id(sectionId, currentUserProvider.tenantId())
                .orElseThrow(() -> sectionNotFound(sectionId));
        try {
            bookAccessService.requireCapabilityForUpdate(
                    section.getBook().getId(),
                    BookCapability.MUTATE_MANUSCRIPT_STRUCTURE
            );
        } catch (ResourceNotFoundException exception) {
            throw sectionNotFound(sectionId);
        }
        return section;
    }

    /**
     * Resolves a Section by tenant and then proves the capability on its Book.
     *
     * <p>A Section of another Workspace, of a Book this User has no relationship with, and one whose
     * Book denies the capability all raise the same {@code Section not found}: reaching a Book
     * indirectly through a Section identifier must not reveal that the Section exists.
     */
    private BookSection requireSection(UUID sectionId, BookCapability capability) {
        BookSection section = sectionRepository.findByIdAndBook_Tenant_Id(sectionId, currentUserProvider.tenantId())
                .orElseThrow(() -> sectionNotFound(sectionId));
        try {
            bookAccessService.requireCapability(section.getBook().getId(), capability);
        } catch (ResourceNotFoundException exception) {
            throw sectionNotFound(sectionId);
        }
        return section;
    }

    private ResourceNotFoundException sectionNotFound(UUID sectionId) {
        return new ResourceNotFoundException("Section not found: " + sectionId);
    }

    private <T> void applyReorder(
            List<T> children,
            List<UUID> orderedIds,
            Function<T, UUID> idGetter,
            OrderSetter<T> orderSetter,
            String childName
    ) {
        if (orderedIds.size() != new HashSet<>(orderedIds).size()) {
            throw new BadRequestException("Duplicate IDs are not allowed");
        }
        if (orderedIds.size() != children.size()) {
            throw new BadRequestException("Reorder list must include all " + childName + " for the parent");
        }

        Map<UUID, T> childrenById = children.stream()
                .collect(Collectors.toMap(idGetter, Function.identity()));

        if (!childrenById.keySet().equals(new HashSet<>(orderedIds))) {
            throw new BadRequestException("All IDs must exist and belong to the parent");
        }

        for (int index = 0; index < orderedIds.size(); index++) {
            T child = childrenById.get(orderedIds.get(index));
            orderSetter.setSortOrder(child, index);
        }
    }

    @FunctionalInterface
    private interface OrderSetter<T> {
        void setSortOrder(T child, int sortOrder);
    }
}
