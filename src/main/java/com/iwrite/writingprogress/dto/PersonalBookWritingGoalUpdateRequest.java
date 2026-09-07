package com.iwrite.writingprogress.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.iwrite.common.exception.BadRequestException;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.DayOfWeek;
import java.util.List;

/**
 * Partial update of the authenticated User's own Personal Book Writing Goal (#206).
 *
 * <p>Presence is tracked per field so an explicit {@code null} target clears the goal while an absent
 * field leaves it alone. Clearing restores the intentional absence of a target; it is not a target of
 * zero.
 *
 * <p>The request describes the goal and nothing else. It cannot carry Book settings, another User's
 * goal, a Book Role or a capability: unknown fields are rejected instead of silently ignored, so a
 * hidden field can never be mass assigned into a surface this contract does not own.
 *
 * <p>{@code expectedRevision} is required: it names the goal state this save was decided against, so
 * two tabs that both read the same goal cannot both succeed with the later one silently discarding
 * the earlier choice. It is not optional, because a save that may omit it is a lost update the
 * contract still allows.
 *
 * <p>At least one of the two halves must also be named. A body carrying only {@code expectedRevision}
 * changes nothing, so accepting it would turn a no-op into an observable mutation: it would materialize
 * the goal row and advance the revision, and a real edit another tab decided against the previous
 * revision would then be refused by a request that changed nothing.
 *
 * <p>Only the three fields named by explicit setters are deserializable. Jackson would otherwise infer
 * a property from each public getter and pull in the matching private field as its mutator, so the
 * internal presence flags would be known — therefore quietly accepted — fields instead of unknown
 * ones: a body naming {@code dailyTargetWordCountPresent} would behave as if the target had been sent
 * explicitly as {@code null}, clearing a chosen target and advancing the revision without ever naming
 * the target. Hiding the accessors from Jackson sends those names to the any-setter below, which is
 * the same {@code 400} any other field this contract does not own already gets.
 */
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE
)
public class PersonalBookWritingGoalUpdateRequest {

    @Positive
    private Integer dailyTargetWordCount;
    private boolean dailyTargetWordCountPresent;

    private List<DayOfWeek> plannedWritingDays;
    private boolean plannedWritingDaysPresent;

    @NotNull
    @PositiveOrZero
    private Integer expectedRevision;

    public Integer expectedRevision() {
        return expectedRevision;
    }

    public Integer dailyTargetWordCount() {
        return dailyTargetWordCount;
    }

    public boolean isDailyTargetWordCountPresent() {
        return dailyTargetWordCountPresent;
    }

    public List<DayOfWeek> plannedWritingDays() {
        return plannedWritingDays;
    }

    public boolean isPlannedWritingDaysPresent() {
        return plannedWritingDaysPresent;
    }

    /**
     * Whether this request names anything to change. A save must move at least one half of the goal;
     * see the type comment for why an empty one is a mutation rather than a no-op.
     */
    @AssertTrue(message = "a save must change dailyTargetWordCount or plannedWritingDays")
    public boolean isGoalChangeNamed() {
        return dailyTargetWordCountPresent || plannedWritingDaysPresent;
    }

    @JsonSetter("expectedRevision")
    public void setExpectedRevision(Integer expectedRevision) {
        this.expectedRevision = expectedRevision;
    }

    @JsonSetter("dailyTargetWordCount")
    public void setDailyTargetWordCount(Integer dailyTargetWordCount) {
        this.dailyTargetWordCount = dailyTargetWordCount;
        this.dailyTargetWordCountPresent = true;
    }

    @JsonSetter("plannedWritingDays")
    public void setPlannedWritingDays(List<DayOfWeek> plannedWritingDays) {
        this.plannedWritingDays = plannedWritingDays;
        this.plannedWritingDaysPresent = true;
    }

    @JsonAnySetter
    void rejectUnknownField(String name, Object ignoredValue) {
        throw new BadRequestException("Unknown personal writing goal field: " + name);
    }
}
