package com.iwrite.common.exception;

import com.iwrite.auth.AuthMessages;
import com.iwrite.auth.LoginRateLimitExceededException;
import com.iwrite.auth.RegistrationMessages;
import com.iwrite.auth.RegistrationRateLimitExceededException;
import com.iwrite.llm.gateway.LlmExecutionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException exception) {
        return buildResponse(HttpStatus.NOT_FOUND, List.of(exception.getMessage()));
    }

    @ExceptionHandler({ NoHandlerFoundException.class, NoResourceFoundException.class })
    public ResponseEntity<ApiErrorResponse> handleUnmappedRoute(Exception exception) {
        return buildResponse(HttpStatus.NOT_FOUND, List.of("Resource not found"));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(BadRequestException exception) {
        return buildResponse(HttpStatus.BAD_REQUEST, List.of(exception.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(ConflictException exception) {
        return buildResponse(HttpStatus.CONFLICT, List.of(exception.getMessage()));
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleServiceUnavailable(ServiceUnavailableException exception) {
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, List.of(exception.getMessage()));
    }

    @ExceptionHandler(LlmExecutionException.class)
    public ResponseEntity<ApiErrorResponse> handleLlmExecution(LlmExecutionException exception) {
        return buildResponse(
                HttpStatus.SERVICE_UNAVAILABLE,
                List.of("AI scene analysis could not be completed.")
        );
    }

    /**
     * A session whose backing user, tenant or membership no longer resolves. Reported separately
     * from a failed login so the client can tell "log in again" from "those credentials are wrong",
     * without either message revealing whether an account exists.
     *
     * <p>Applies regardless of which endpoint first notices the revoked membership: the old
     * {@code JSESSIONID} is invalidated and the security context cleared here, not only on
     * {@code /api/auth/me}. Otherwise a session revoked on a tenant-scoped endpoint like
     * {@code /api/books} would keep answering 401 without ever being destroyed, and recreating the
     * same membership would silently re-authorize the old cookie without a new login.
     *
     * <p>{@code invalidate} can race {@code AuthController#discardPartialSession}, which invalidates
     * the same session on a rolled-back registration before this handler ever runs — the second
     * caller finds it already gone, which is fine, not a state to fail on.
     */
    @ExceptionHandler(SessionAuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidSession(
            SessionAuthenticationException exception, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException alreadyInvalidated) {
                // Already invalidated by the other cleanup path; nothing left to do.
            }
        }
        SecurityContextHolder.clearContext();
        return buildResponse(HttpStatus.UNAUTHORIZED, List.of(AuthMessages.SESSION_EXPIRED));
    }

    /**
     * Every authentication failure collapses to one message. Unknown email, wrong password, missing
     * membership and ambiguous membership are indistinguishable from outside, and the exception
     * message is deliberately discarded so no internal cause leaks into the response.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException exception) {
        return buildResponse(HttpStatus.UNAUTHORIZED, List.of(AuthMessages.INVALID_CREDENTIALS));
    }

    /** Silent on which dimension (origin or account) tripped and on whether the account exists. */
    @ExceptionHandler(LoginRateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleLoginRateLimited(LoginRateLimitExceededException exception) {
        return buildResponse(HttpStatus.TOO_MANY_REQUESTS, List.of(AuthMessages.TOO_MANY_LOGIN_ATTEMPTS));
    }

    /** Registration's own budget, tracked separately from {@link #handleLoginRateLimited}. */
    @ExceptionHandler(RegistrationRateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleRegistrationRateLimited(RegistrationRateLimitExceededException exception) {
        return buildResponse(HttpStatus.TOO_MANY_REQUESTS, List.of(RegistrationMessages.TOO_MANY_REGISTRATION_ATTEMPTS));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        List<String> messages = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();

        return buildResponse(HttpStatus.BAD_REQUEST, messages);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        List<String> messages = exception.getConstraintViolations()
                .stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .toList();

        return buildResponse(HttpStatus.BAD_REQUEST, messages);
    }

    /**
     * A body that could not be bound. A request rejected by the contract itself — an unknown field a
     * strict request refuses to mass assign, for instance — carries its own explanation, so the reason
     * reaches the caller instead of being flattened into the generic message. Anything else stays
     * generic: a parser failure must not leak the shape of what it was parsing.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException exception) {
        return buildResponse(HttpStatus.BAD_REQUEST, List.of(rejectionReason(exception).orElse("Invalid request body")));
    }

    private Optional<String> rejectionReason(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof BadRequestException badRequest) {
                return Optional.ofNullable(badRequest.getMessage());
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return Optional.empty();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return buildResponse(HttpStatus.BAD_REQUEST, List.of("Invalid value for " + exception.getName()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, List.of("Unexpected internal error"));
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(HttpStatus status, List<String> messages) {
        ApiErrorResponse response = new ApiErrorResponse(
                OffsetDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                messages);

        return ResponseEntity.status(status).body(response);
    }
}
