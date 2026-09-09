package com.iwrite.profile;

import com.iwrite.auth.RegistrationMessages;
import com.iwrite.auth.WellFormedUtf8;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.timezone.IanaZoneIdValidator;

import java.time.ZoneId;

/**
 * Shared validation for the user-owned fields accepted both at registration and while editing the
 * private profile. Keeping normalization and policy here prevents the two entry points from
 * drifting while leaving password/email registration rules in their existing module.
 */
public final class UserProfileValidator {

    private static final int MAX_DISPLAY_NAME_LENGTH = 255;

    private final IanaZoneIdValidator timeZoneValidator;

    public UserProfileValidator(IanaZoneIdValidator timeZoneValidator) {
        this.timeZoneValidator = timeZoneValidator;
    }

    public String normalizeDisplayName(String rawDisplayName) {
        if (rawDisplayName == null) {
            throw new BadRequestException(RegistrationMessages.INVALID_DISPLAY_NAME);
        }
        String displayName = rawDisplayName.trim();
        if (displayName.isEmpty()
                || !WellFormedUtf8.isWellFormed(displayName)
                || containsControlCharacter(displayName)) {
            throw new BadRequestException(RegistrationMessages.INVALID_DISPLAY_NAME);
        }
        if (displayName.codePointCount(0, displayName.length()) > MAX_DISPLAY_NAME_LENGTH) {
            throw new BadRequestException(RegistrationMessages.DISPLAY_NAME_TOO_LONG);
        }
        return displayName;
    }

    public String validateTimeZone(String rawTimeZone) {
        try {
            ZoneId zoneId = timeZoneValidator.validate(rawTimeZone);
            return zoneId.getId();
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(RegistrationMessages.INVALID_TIME_ZONE);
        }
    }

    private static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }
}
