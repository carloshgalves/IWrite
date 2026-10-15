package com.iwrite.auth;

import com.iwrite.auth.dto.RegisterRequest;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ConflictException;
import com.iwrite.common.timezone.IanaZoneIdValidator;
import com.iwrite.profile.UserProfileValidator;
import com.iwrite.tenant.entity.Tenant;
import com.iwrite.tenant.entity.TenantMembership;
import com.iwrite.tenant.entity.TenantMembershipRole;
import com.iwrite.tenant.repository.TenantMembershipRepository;
import com.iwrite.tenant.repository.TenantRepository;
import com.iwrite.user.entity.User;
import com.iwrite.user.entity.UserCredential;
import com.iwrite.user.entity.UserPersona;
import com.iwrite.user.entity.UserPersonaType;
import com.iwrite.user.repository.UserCredentialRepository;
import com.iwrite.user.repository.UserPersonaRepository;
import com.iwrite.user.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Creates a user's personal workspace end to end: {@code User}, {@code UserCredential}, a personal
 * {@code Tenant}, an {@code OWNER} {@code TenantMembership}, and the primary {@code UserPersona} —
 * one transaction, so a failure at any step leaves nothing behind. Session establishment is not
 * part of this service: {@link AuthController#register} re-authenticates through the same
 * {@code AuthenticationManager} path {@link AuthController#login} uses, once this method returns.
 */
@Service
public class RegistrationService {

    // Same shape as the existing recipient-email check in BookCollaborationInvitationService: kept
    // as its own small copy rather than shared, since the two validate different bounded concepts
    // (an account's login email vs. an invitation's recipient email) that only look alike today.
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    // Matches users.display_name / tenants.name / users.email varchar(255) (V20). Counted in code
    // points, like Postgres' character length, so a surrogate pair (e.g. an emoji) counts once.
    private static final int MAX_NAME_LENGTH = 255;
    private static final String TENANT_NAME_PREFIX = "Espaço de ";

    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final TenantRepository tenantRepository;
    private final TenantMembershipRepository membershipRepository;
    private final UserPersonaRepository personaRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserProfileValidator profileValidator;

    public RegistrationService(
            UserRepository userRepository,
            UserCredentialRepository credentialRepository,
            TenantRepository tenantRepository,
            TenantMembershipRepository membershipRepository,
            UserPersonaRepository personaRepository,
            PasswordEncoder passwordEncoder,
            IanaZoneIdValidator timeZoneValidator
    ) {
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.personaRepository = personaRepository;
        this.passwordEncoder = passwordEncoder;
        this.profileValidator = new UserProfileValidator(timeZoneValidator);
    }

    @Transactional
    public void register(RegisterRequest request) {
        String email = EmailNormalizer.normalize(request.email());
        validateEmailFormat(email);
        validatePassword(request.password(), request.passwordConfirmation());
        String displayName = profileValidator.normalizeDisplayName(request.displayName());
        UserPersonaType persona = parsePersona(request.primaryPersona());
        String timeZoneId = profileValidator.validateTimeZone(request.timeZone());

        // Fast, cheap precheck for the ordinary sequential-duplicate case: refuses before bcrypt
        // ever runs. The saveAndFlush below is what actually closes the concurrent race.
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ConflictException(RegistrationMessages.EMAIL_ALREADY_IN_USE);
        }

        User user = new User();
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setTimeZoneId(timeZoneId);

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            // Two requests passed the precheck together; the unique constraint on users.email is
            // the actual arbiter. Nothing else in this method has run yet, so there is nothing to
            // roll back beyond this one failed insert. Any other constraint violation is not a
            // duplicate email and must not be reported as one — it propagates as-is and surfaces
            // as GlobalExceptionHandler's generic 500, exactly like any other unexpected failure.
            if (!violatesEmailUniqueConstraint(exception)) {
                throw exception;
            }
            throw new ConflictException(RegistrationMessages.EMAIL_ALREADY_IN_USE);
        }

        UserCredential credential = new UserCredential();
        credential.setUserId(user.getId());
        credential.setPasswordHash(passwordEncoder.encode(request.password()));
        credentialRepository.save(credential);

        Tenant tenant = new Tenant();
        tenant.setName(deriveTenantName(displayName));
        tenant.setDefaultTimeZoneId(timeZoneId);
        tenantRepository.save(tenant);

        TenantMembership membership = new TenantMembership();
        membership.setTenant(tenant);
        membership.setUser(user);
        membership.setRole(TenantMembershipRole.OWNER);
        membershipRepository.save(membership);

        UserPersona userPersona = new UserPersona();
        userPersona.setUserId(user.getId());
        userPersona.setPersona(persona);
        userPersona.setPrimary(true);
        personaRepository.save(userPersona);
    }

    private void validateEmailFormat(String email) {
        // \s in EMAIL_PATTERN excludes whitespace, not control characters generally: NUL and other
        // C0/C1 controls are neither \s nor caught by the regex, and users.email is a Postgres
        // varchar, which rejects NUL outright — without this check that insert throws a generic
        // DataIntegrityViolationException that is not the email-uniqueness violation below, and
        // surfaces as a 500 instead of a 400. Checked ahead of the regex too, so a control character
        // never gets a chance to slip through as "not \s".
        if (containsControlCharacter(email)) {
            throw new BadRequestException(RegistrationMessages.INVALID_EMAIL);
        }
        // ASCII-only policy (#149 review): see EmailNormalizer's class doc. Checked ahead of the
        // format regex and any lookup/bcrypt/write, same ordering as the control-character check.
        if (!EmailNormalizer.isAscii(email)) {
            throw new BadRequestException(RegistrationMessages.INVALID_EMAIL);
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new BadRequestException(RegistrationMessages.INVALID_EMAIL);
        }
        // Checked ahead of any write or bcrypt call: users.email is varchar(255), and a value this
        // long must fail as a validation error, never masquerade as a duplicate-email 409.
        if (email.codePointCount(0, email.length()) > MAX_NAME_LENGTH) {
            throw new BadRequestException(RegistrationMessages.EMAIL_TOO_LONG);
        }
    }

    // Character.isISOControl(int), not a Unicode category regex: this must catch exactly Cc
    // (control) code points, never Cf (format) ones like ZWJ that legitimate emoji sequences and
    // some scripts depend on.
    private static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    /**
     * "Espaço de &lt;displayName&gt;", truncated to fit {@code tenants.name varchar(255)}. Only the
     * derived part is ever cut — the user's own {@code displayName} on {@code User} always stays
     * whole — and the cut lands on a code point boundary so a surrogate pair (e.g. an emoji) is
     * never split in two.
     */
    static String deriveTenantName(String displayName) {
        String candidate = TENANT_NAME_PREFIX + displayName;
        if (candidate.codePointCount(0, candidate.length()) <= MAX_NAME_LENGTH) {
            return candidate;
        }
        int allowedNameCodePoints = MAX_NAME_LENGTH - TENANT_NAME_PREFIX.codePointCount(0, TENANT_NAME_PREFIX.length());
        int truncateAt = displayName.offsetByCodePoints(0, allowedNameCodePoints);
        return TENANT_NAME_PREFIX + displayName.substring(0, truncateAt);
    }

    /** Walks the full cause chain: Hibernate reports the constraint name structurally, but a test
     *  (or a driver that doesn't) may only carry it in the message, so both are checked. */
    private boolean violatesEmailUniqueConstraint(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException constraintViolation
                    && "uk_users_email".equals(constraintViolation.getConstraintName())) {
                return true;
            }
            if (cause.getMessage() != null && cause.getMessage().contains("uk_users_email")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private void validatePassword(String password, String confirmation) {
        if (!password.equals(confirmation)) {
            throw new BadRequestException(RegistrationMessages.PASSWORD_CONFIRMATION_MISMATCH);
        }
        if (!PasswordPolicy.isValid(password)) {
            throw new BadRequestException(RegistrationMessages.WEAK_PASSWORD);
        }
    }

    private UserPersonaType parsePersona(String rawPersona) {
        try {
            return UserPersonaType.valueOf(rawPersona.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(RegistrationMessages.INVALID_PERSONA);
        }
    }

}
