package com.iwrite.writingprogress.controller;

import com.iwrite.audit.annotation.AuditedOperation;
import com.iwrite.audit.entity.AuditAction;
import com.iwrite.audit.entity.AuditResourceType;
import com.iwrite.writingprogress.dto.PersonalBookWritingGoalResponse;
import com.iwrite.writingprogress.dto.PersonalBookWritingGoalUpdateRequest;
import com.iwrite.writingprogress.service.PersonalBookWritingGoalService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The Personal Book Writing Goal surface (#206), kept apart from the shared Book settings of
 * {@code PATCH /api/books/{bookId}} on purpose.
 *
 * <p>The path names the Book, never the User: the goal is always the authenticated caller's own, so
 * no request can read or write another collaborator's target. A caller without
 * {@code MANAGE_OWN_PERSONAL_WRITING_GOAL} gets the same non-enumerable not-found answer as one who
 * cannot see the Book at all.
 */
@RestController
@RequestMapping("/api/books/{bookId}/writing-goal")
public class PersonalBookWritingGoalController {

    private final PersonalBookWritingGoalService personalBookWritingGoalService;

    public PersonalBookWritingGoalController(PersonalBookWritingGoalService personalBookWritingGoalService) {
        this.personalBookWritingGoalService = personalBookWritingGoalService;
    }

    @GetMapping
    public PersonalBookWritingGoalResponse getGoal(@PathVariable UUID bookId) {
        return personalBookWritingGoalService.getGoal(bookId);
    }

    @PatchMapping
    @AuditedOperation(
            action = AuditAction.PERSONAL_WRITING_GOAL_UPDATED,
            resourceType = AuditResourceType.BOOK,
            resourceId = "#bookId"
    )
    public PersonalBookWritingGoalResponse updateGoal(
            @PathVariable UUID bookId,
            @Valid @RequestBody PersonalBookWritingGoalUpdateRequest request
    ) {
        return personalBookWritingGoalService.updateGoal(bookId, request);
    }
}
