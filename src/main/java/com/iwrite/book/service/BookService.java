package com.iwrite.book.service;

import com.iwrite.book.authorization.BookCapability;
import com.iwrite.book.dto.BookRequest;
import com.iwrite.book.dto.BookResponse;
import com.iwrite.book.dto.BookUpdateRequest;
import com.iwrite.book.entity.Book;
import com.iwrite.book.entity.BookRole;
import com.iwrite.book.entity.BookStatus;
import com.iwrite.book.repository.BookCollaboratorRepository;
import com.iwrite.book.repository.BookRepository;
import com.iwrite.book.repository.BookRoleAssignment;
import com.iwrite.common.validation.RequestValidation;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.context.CurrentUserMembershipService;
import com.iwrite.user.context.CurrentUserProvider;
import com.iwrite.user.repository.UserRepository;
import com.iwrite.writingprogress.service.WritingScheduleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BookService {

    private final BookRepository bookRepository;
    private final BookCollaboratorRepository collaboratorRepository;
    private final WritingScheduleService writingScheduleService;
    private final TenantRepository tenantRepository;
    private final CurrentUserProvider currentUserProvider;
    private final CurrentUserMembershipService currentUserMembershipService;
    private final BookAccessService bookAccessService;
    private final UserRepository userRepository;

    public BookService(
            BookRepository bookRepository,
            BookCollaboratorRepository collaboratorRepository,
            WritingScheduleService writingScheduleService,
            TenantRepository tenantRepository,
            CurrentUserProvider currentUserProvider,
            CurrentUserMembershipService currentUserMembershipService,
            BookAccessService bookAccessService,
            UserRepository userRepository
    ) {
        this.bookRepository = bookRepository;
        this.collaboratorRepository = collaboratorRepository;
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
        Map<UUID, BookRole> rolesByBookId = collaboratorRepository.findRolesByTenantIdAndUserId(tenantId, userId)
                .stream()
                .collect(Collectors.toMap(BookRoleAssignment::bookId, BookRoleAssignment::role));
        return bookRepository.findAllAccessibleByTenantIdAndUserId(tenantId, userId)
                .stream()
                .map(book -> BookResponse.fromEntity(
                        book,
                        writingScheduleService.getActivePlannedWritingDays(book),
                        bookAccessService.accessContextFor(book, userId, rolesByBookId.get(book.getId()))
                ))
                .toList();
    }

    @Transactional
    public BookResponse findById(UUID bookId) {
        BookAccessService.AccessibleBook accessible = bookAccessService.resolveAccessibleBook(bookId);
        Book book = accessible.book();
        return BookResponse.fromEntity(
                book,
                writingScheduleService.getActivePlannedWritingDays(book),
                accessible.access()
        );
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
        book.setDailyTargetWordCount(request.dailyTargetWordCount());

        Book savedBook = bookRepository.save(book);
        List<DayOfWeek> plannedWritingDays = writingScheduleService.createInitialSchedule(savedBook, request.plannedWritingDays());
        return BookResponse.fromEntity(savedBook, plannedWritingDays, bookAccessService.accessContextFor(savedBook, userId, null));
    }

    @Transactional
    public BookResponse update(UUID bookId, BookUpdateRequest request) {
        BookAccessService.AccessibleBook accessible = bookAccessService.resolveAccessibleBook(bookId);
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
        if (request.isDailyTargetWordCountPresent()) {
            book.setDailyTargetWordCount(request.dailyTargetWordCount());
        }

        List<DayOfWeek> plannedWritingDays = request.isPlannedWritingDaysPresent()
                ? writingScheduleService.changeSchedule(book, request.plannedWritingDays())
                : writingScheduleService.getActivePlannedWritingDays(book);

        return BookResponse.fromEntity(book, plannedWritingDays, accessible.access());
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
