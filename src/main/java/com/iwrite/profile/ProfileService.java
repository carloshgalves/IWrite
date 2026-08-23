package com.iwrite.profile;

import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.timezone.IanaZoneIdValidator;
import com.iwrite.profile.dto.ProfileResponse;
import com.iwrite.profile.dto.ProfileUpdateRequest;
import com.iwrite.user.entity.User;
import com.iwrite.user.entity.UserPersona;
import com.iwrite.user.entity.UserPersonaType;
import com.iwrite.user.repository.UserPersonaRepository;
import com.iwrite.user.repository.UserRepository;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ProfileService {

    private final UserRepository userRepository;
    private final UserPersonaRepository personaRepository;
    private final UserProfileValidator profileValidator;

    public ProfileService(
            UserRepository userRepository,
            UserPersonaRepository personaRepository,
            IanaZoneIdValidator timeZoneValidator
    ) {
        this.userRepository = userRepository;
        this.personaRepository = personaRepository;
        this.profileValidator = new UserProfileValidator(timeZoneValidator);
    }

    @Transactional(readOnly = true)
    public ProfileResponse get(UUID authenticatedUserId) {
        User user = requireUser(authenticatedUserId);
        return response(user, requireValidStoredPersonas(authenticatedUserId));
    }

    @Transactional
    public ProfileResponse update(UUID authenticatedUserId, ProfileUpdateRequest request) {
        // Validate the complete command before changing managed entities or issuing a write. This
        // makes ordinary validation failures side-effect free; the transaction remains the final
        // atomicity boundary for persistence failures after this point.
        String displayName = profileValidator.normalizeDisplayName(request.displayName());
        String timeZone = profileValidator.validateTimeZone(request.timeZone());
        List<UserPersonaType> requestedPersonas = parsePersonas(request.personas());
        UserPersonaType requestedPrimary = parsePersona(request.primaryPersona());
        if (!requestedPersonas.contains(requestedPrimary)) {
            throw new BadRequestException(ProfileMessages.PRIMARY_PERSONA_REQUIRED);
        }

        // Serializes two profile reconciliations for the same identity. This is not authorization:
        // authenticatedUserId came from the server-side principal, never from the request body.
        User user = userRepository.findByIdForUpdate(authenticatedUserId)
                .orElseThrow(this::invalidSession);
        List<UserPersona> existing = personaRepository.findAllByUserId(authenticatedUserId);
        Map<UserPersonaType, UserPersona> byType = new LinkedHashMap<>();
        existing.forEach(persona -> byType.put(persona.getPersona(), persona));

        UserPersona currentPrimary = existing.stream().filter(UserPersona::isPrimary).findFirst().orElse(null);
        if (currentPrimary == null || currentPrimary.getPersona() != requestedPrimary) {
            // Executes as its own SQL update before Hibernate can flush the new `true`. The partial
            // unique index therefore never observes old=true and new=true at the same time.
            personaRepository.clearPrimary(authenticatedUserId);
            existing.forEach(persona -> persona.setPrimary(false));
        }

        Set<UserPersonaType> requestedSet = EnumSet.copyOf(requestedPersonas);
        existing.stream()
                .filter(persona -> !requestedSet.contains(persona.getPersona()))
                .forEach(personaRepository::delete);

        List<UserPersona> reconciled = new ArrayList<>();
        for (UserPersonaType type : requestedPersonas) {
            UserPersona persona = byType.get(type);
            if (persona == null) {
                persona = new UserPersona();
                persona.setUserId(authenticatedUserId);
                persona.setPersona(type);
            }
            persona.setPrimary(type == requestedPrimary);
            reconciled.add(persona);
        }

        user.setDisplayName(displayName);
        user.setTimeZoneId(timeZone);
        personaRepository.saveAll(reconciled);
        personaRepository.flush();
        return response(user, reconciled);
    }

    private List<UserPersonaType> parsePersonas(List<String> rawPersonas) {
        if (rawPersonas == null || rawPersonas.isEmpty()) {
            throw new BadRequestException(ProfileMessages.PERSONAS_REQUIRED);
        }
        List<UserPersonaType> personas = rawPersonas.stream().map(this::parsePersona).toList();
        if (EnumSet.copyOf(personas).size() != personas.size()) {
            throw new BadRequestException(ProfileMessages.DUPLICATE_PERSONA);
        }
        return personas;
    }

    private UserPersonaType parsePersona(String rawPersona) {
        if (rawPersona == null) {
            throw new BadRequestException(ProfileMessages.INVALID_PERSONA);
        }
        try {
            // Same normalization accepted by public registration.
            return UserPersonaType.valueOf(rawPersona.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(ProfileMessages.INVALID_PERSONA);
        }
    }

    private List<UserPersona> requireValidStoredPersonas(UUID userId) {
        List<UserPersona> personas = personaRepository.findAllByUserId(userId);
        if (personas.isEmpty() || personas.stream().filter(UserPersona::isPrimary).count() != 1) {
            throw new IllegalStateException("Stored profile must have exactly one primary persona");
        }
        return personas;
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow(this::invalidSession);
    }

    private SessionAuthenticationException invalidSession() {
        return new SessionAuthenticationException("Authenticated profile no longer exists");
    }

    private ProfileResponse response(User user, List<UserPersona> personas) {
        List<ProfileResponse.PersonaResponse> personaResponses = personas.stream()
                .sorted((left, right) -> left.getPersona().compareTo(right.getPersona()))
                .map(persona -> new ProfileResponse.PersonaResponse(
                        persona.getPersona().name(), persona.isPrimary()))
                .toList();
        return new ProfileResponse(
                user.getDisplayName(),
                user.getEmail(),
                user.getTimeZoneId(),
                personaResponses
        );
    }
}
