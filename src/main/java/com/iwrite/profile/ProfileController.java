package com.iwrite.profile;

import com.iwrite.auth.IWriteUserDetails;
import com.iwrite.profile.dto.ProfileResponse;
import com.iwrite.profile.dto.ProfileUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ProfileResponse get(@AuthenticationPrincipal IWriteUserDetails principal) {
        return profileService.get(principal.userId());
    }

    @PatchMapping
    public ProfileResponse update(
            @AuthenticationPrincipal IWriteUserDetails principal,
            @Valid @RequestBody ProfileUpdateRequest request
    ) {
        return profileService.update(principal.userId(), request);
    }
}
