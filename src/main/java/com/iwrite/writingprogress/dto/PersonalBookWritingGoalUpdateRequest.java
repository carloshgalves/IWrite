package com.iwrite.writingprogress.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.iwrite.common.exception.BadRequestException;
import jakarta.validation.constraints.Positive;

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
 */
public class PersonalBookWritingGoalUpdateRequest {

    @Positive
    private Integer dailyTargetWordCount;
    private boolean dailyTargetWordCountPresent;

    private List<DayOfWeek> plannedWritingDays;
    private boolean plannedWritingDaysPresent;

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
