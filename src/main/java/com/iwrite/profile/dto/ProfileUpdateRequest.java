package com.iwrite.profile.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ProfileUpdateRequest(
        @NotNull String displayName,
        @NotNull String timeZone,
        @NotNull List<String> personas,
        @NotNull String primaryPersona
) {
}
