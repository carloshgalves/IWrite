package com.iwrite.book.service;

import com.iwrite.book.authorization.BookCapability;
import com.iwrite.book.dto.BookRequest;
import com.iwrite.book.dto.BookResponse;
import com.iwrite.book.dto.BookUpdateRequest;
import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookStatus;
import com.iwrite.book.repository.BookRepository;
import com.iwrite.common.validation.RequestValidation;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.context.CurrentUserMembershipService;
import com.iwrite.user.context.CurrentUserProvider;
import com.iwrite.user.repository.UserRepository;
import com.iwrite.writingprogress.service.WritingScheduleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class BookService {

    private final BookRepository bookRepository;
    private final WritingScheduleService writingScheduleService;
    private final TenantRepository tenantRepository;
    private final CurrentUserProvider currentUserProvider;
    private final CurrentUserMembershipService currentUserMembershipService;
    private final BookAccessService bookAccessService;
    private final UserRepository userRepository;

    public BookService(
            BookRepository bookRepository,
            WritingScheduleService writingScheduleService,
            TenantRepository tenantRepository,
            CurrentUserProvider currentUserProvider,
            CurrentUserMembershipService currentUserMembershipService,
            BookAccessService bookAccessService,
            UserRepository userRepository
    ) {
        this.bookRepository = bookRepository;
        this.writingScheduleService = writingScheduleService;
        this.tenantRepository = tenantRepository;
        this.currentUserProvider = currentUserProvider;
        this.currentUserMembershipService = currentUserMembershipService;
        this.bookAccessService = bookAccessService;
        this.userRepository = userRepository;
    }

    @Transactional
    public List<BookResponse> findAll() {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        UUID tenantId = currentUserProvider.tenantId();
        return bookRepository.findAllAccessibleWithRoleByTenantIdAndUserId(tenantId, userId)
                .stream()
                .map(accessible -> BookResponse.fromEntity(
                        accessible.book(),
                        bookAccessService.accessContextFor(accessible.book(), userId, accessible.role())
                ))
                .toList();
    }

    @Transactional
    public BookResponse findById(UUID bookId) {
        BookAccessService.AccessibleBook accessible = bookAccessService.resolveAccessibleBook(bookId);
        return BookResponse.fromEntity(accessible.book(), accessible.access());
    }

    @Transactional
    public BookResponse create(BookRequest request) {
        UUID userId = currentUserMembershipService.requireCurrentUserMemberId();
        Book book = new Book();
        book.setTenant(tenantRepository.getReferenceById(currentUserProvider.tenantId()));
        book.setOwner(userRepository.getReferenceById(userId));
        book.setTitle(request.title());
        book.setSubtitle(request.subtitle());
        book.setDescription(request.description());
        book.setStatus(request.status() == null ? BookStatus.PLANNING : request.status());
        book.setTargetWordCount(request.targetWordCount());

        Book savedBook = bookRepository.save(book);
        // The creator's own writing routine starts with the Book so their progress has a schedule to be
        // measured against from day one. It is their Personal Book Writing Goal, not a Book setting, so
        // it takes the default routine and is changed through the writing-goal contract.
        writingScheduleService.createInitialSchedule(savedBook, null);
        return BookResponse.fromEntity(savedBook, bookAccessService.accessContextFor(savedBook, userId, null));
    }

    /**
     * Changes the shared settings of the Book: metadata, {@link BookStatus} and the optional Book-wide
     * target. The minimum capability is {@link BookCapability#EDIT_BOOK_SETTINGS}, which the policy
     * grants to the Book Owner and, for compatibility until the cutover, to the legacy collaboration
     * role. An Author, Editor or Reader cannot reshape data every collaborator shares.
     *
     * <p>Nothing here touches a Personal Book Writing Goal: a daily target and planned writing days
     * are owned by one User and are changed only through their own goal contract.
     */
    @Transactional
    public BookResponse update(UUID bookId, BookUpdateRequest request) {
        BookAccessService.AccessibleBook accessible =
                bookAccessService.requireAccessibleBookForUpdate(bookId, BookCapability.EDIT_BOOK_SETTINGS);
        Book book = accessible.book();
        RequestValidation.rejectBlankWhenPresent("title", request.title());

        if (request.title() != null) {
            book.setTitle(request.title());
        }
        if (request.subtitle() != null) {
            book.setSubtitle(request.subtitle());
        }
        if (request.description() != null) {
            book.setDescription(request.description());
        }
        if (request.status() != null) {
            book.setStatus(request.status());
        }
        if (request.isTargetWordCountPresent()) {
            book.setTargetWordCount(request.targetWordCount());
        }

        return BookResponse.fromEntity(book, accessible.access());
    }

    @Transactional
    public void delete(UUID bookId) {
        Book book = bookAccessService.requireCapability(bookId, BookCapability.DELETE_BOOK);
        bookRepository.delete(book);
    }

    @Transactional(readOnly = true)
    public Book getBook(UUID bookId) {
        return bookAccessService.requireBookReadAccess(bookId);
    }

    @Transactional
    public Book getBookForWordCountUpdate(UUID bookId) {
        return bookAccessService.requireBookEditAccessForUpdate(bookId);
    }
}
