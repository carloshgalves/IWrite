package com.iwrite.profile.dto;

import java.util.List;

public record ProfileResponse(
        String displayName,
        String email,
        String timeZone,
        List<PersonaResponse> personas
) {
    public record PersonaResponse(String type, boolean primary) {
    }
}
