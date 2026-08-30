package com.wander.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

import com.wander.auth.dto.ChangePasswordRequest;
import com.wander.auth.dto.LoginRequest;
import com.wander.auth.dto.RegisterRequest;
import com.wander.auth.dto.SessionUser;
import com.wander.auth.dto.UpdateProfileRequest;
import com.wander.common.ApiError;
import com.wander.common.PublicEndpoint;
import com.wander.security.WanderUser;
import com.wander.user.User;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextHolderStrategy contextHolderStrategy = SecurityContextHolder
            .getContextHolderStrategy();
    private final UserAccountService accounts;
    private final LoginThrottle throttle;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public AuthController(AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository, UserAccountService accounts,
            LoginThrottle throttle, FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.accounts = accounts;
        this.throttle = throttle;
        this.sessions = sessions;
    }

    @PublicEndpoint
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionUser register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        User created = accounts.register(request.email(), request.displayName(), request.password(),
                request.inviteToken());
        // Log the new account straight in — a register call that then makes the
        // client POST /login separately is two round trips for no gain.
        authenticate(created.getEmail(), request.password(), httpRequest, httpResponse);
        return new SessionUser(created.getId(), created.getEmail(), created.getDisplayName(), created.getRole());
    }

    /**
     * The only endpoint here that is worth guessing at, and the only one with a
     * throttle in front of it — see {@code LoginThrottle}.
     *
     * The gate is closed *before* the password is verified, so a spent counter
     * costs a map lookup rather than a bcrypt round: hashing on behalf of an
     * attacker is how a guessing attempt becomes an outage. A wrong password is
     * still a 401 exactly as it was; only the reply after too many of them
     * changes, to a 429 that says nothing about whether the address has an
     * account.
     */
    @PublicEndpoint
    @PostMapping("/login")
    public SessionUser login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String address = clientAddress(httpRequest);
        throttle.check(request.email(), address);
        try {
            Authentication authentication = authenticate(request.email(), request.password(), httpRequest,
                    httpResponse);
            throttle.succeeded(request.email(), address);
            return SessionUser.from((WanderUser) authentication.getPrincipal());
        } catch (AuthenticationException ex) {
            throttle.failed(request.email(), address);
            // Rethrown untouched: ExceptionTranslationFilter turns it into the
            // bare 401 the SPA expects, and a message of our own here would be
            // the place somebody eventually leaks "no such account".
            throw ex;
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest httpRequest) {
        // Invalidating the session is the logout: nothing survives on the client
        // that could still authenticate a request.
        contextHolderStrategy.clearContext();
        if (httpRequest.getSession(false) != null) {
            httpRequest.getSession(false).invalidate();
        }
    }

    /**
     * Rename yourself.
     *
     * The subtlety is the last half of it. The principal is written into the
     * session when you sign in and read back from there on every request, so
     * updating the row alone leaves a session still carrying the old name —
     * {@code /me} would answer with it after a reload, and the change would
     * appear to undo itself. So the security context is rebuilt and saved back
     * into the session here, which is the same thing {@code authenticate} does
     * after a login.
     */
    @PutMapping("/profile")
    public SessionUser updateProfile(@Valid @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal WanderUser principal, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        User updated = accounts.updateProfile(principal.id(), request.displayName());
        WanderUser refreshed = WanderUser.from(updated);

        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(refreshed, null,
                refreshed.getAuthorities());
        SecurityContext context = contextHolderStrategy.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolderStrategy.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        return SessionUser.from(refreshed);
    }

    /**
     * Change your own password. Authenticated like everything else, and the only
     * way a password moves on this instance — there is no reset, because nothing
     * here sends mail.
     *
     * Three things are deliberate.
     *
     * The **current password is required**, which is the entire point: a session
     * cookie somebody else has got hold of must not be enough to take the account
     * for good. Knowing the password is what separates the owner from a borrowed
     * browser.
     *
     * It goes through the **same throttle as signing in**. Verifying a password
     * is verifying a password, whichever endpoint does it, and a second door with
     * no counter on it would be the one an attacker with a stolen session walks
     * through. It also means a bcrypt round is not spent per guess, which is the
     * denial-of-service half of the same argument.
     *
     * A success **ends every other session** for this account. Somebody changing
     * their password because they think it leaked expects exactly that, and
     * leaving the other sessions alive would make the change worth much less than
     * it appears — the stolen cookie would still work. This session survives, so
     * the person doing it is not signed out of the page they are looking at.
     */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal WanderUser principal, HttpServletRequest httpRequest) {
        String address = clientAddress(httpRequest);
        throttle.check(principal.email(), address);
        try {
            accounts.changePassword(principal.id(), request.currentPassword(), request.newPassword());
        } catch (IncorrectPasswordException ex) {
            throttle.failed(principal.email(), address);
            throw ex;
        }
        throttle.succeeded(principal.email(), address);
        endOtherSessions(principal.email(), httpRequest);
    }

    /**
     * Every session for this account except the one making the request.
     *
     * Spring Session indexes sessions by principal name, so this is a lookup
     * rather than a scan. The current session is skipped by id: the alternative —
     * clearing the lot — signs the user out of the page they just used, which
     * reads as the change having failed.
     */
    private void endOtherSessions(String email, HttpServletRequest httpRequest) {
        String current = httpRequest.getSession(false) == null ? null : httpRequest.getSession(false).getId();
        Map<String, ? extends Session> found = sessions.findByPrincipalName(email);
        found.keySet().stream()
                .filter(id -> !id.equals(current))
                .forEach(sessions::deleteById);
    }

    @GetMapping("/me")
    public SessionUser me(@AuthenticationPrincipal WanderUser principal) {
        return SessionUser.from(principal);
    }

    /**
     * Who is knocking, for the throttle's second counter.
     *
     * This is the client's address rather than the proxy's because
     * `server.forward-headers-strategy: framework` puts Spring's
     * ForwardedHeaderFilter in front of everything, and it rewrites the remote
     * address from X-Forwarded-For. Trusting that header is only safe because
     * nothing but the proxy can reach this port — compose binds 8080 to
     * loopback, which is the same reason the secure-cookie note in .env.example
     * gives. Expose the app port directly and this becomes a header anybody can
     * set, which would make the per-address counter free to evade. It would not
     * weaken the per-email one.
     */
    private static String clientAddress(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null ? "" : address;
    }

    private Authentication authenticate(String email, String password, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        Authentication authentication = authenticationManager
                .authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));

        // Rotate the session id on login so an id an attacker planted beforehand
        // cannot be ridden afterwards. Only when a session already exists:
        // changeSessionId() throws outright if there is none, and a fresh client
        // (no session cookie yet) gets a brand-new id from saveContext below anyway,
        // which is the same protection by a different route. Spring Session
        // implements changeSessionId on its own request wrapper, so this keeps
        // working now that the store is Postgres rather than the heap.
        if (httpRequest.getSession(false) != null) {
            httpRequest.changeSessionId();
        }

        SecurityContext context = contextHolderStrategy.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolderStrategy.setContext(context);
        // Without this the context lives only for this request and the caller is
        // anonymous again on the next one.
        securityContextRepository.saveContext(context, httpRequest, httpResponse);
        return authentication;
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(RegistrationDisabledException.class)
    public ResponseEntity<ApiError> registrationDisabled(RegistrationDisabledException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of(403, "Forbidden", ex.getMessage()));
    }

    /**
     * 400, not 401. A 401 is how the client is told its session has gone, so
     * answering one here would clear the cached identity and bounce somebody to
     * the login page over a mistyped current password.
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(IncorrectPasswordException.class)
    public ResponseEntity<ApiError> incorrectPassword(IncorrectPasswordException ex) {
        return ResponseEntity.badRequest().body(ApiError.of(400, "Bad Request", ex.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(SamePasswordException.class)
    public ResponseEntity<ApiError> samePassword(SamePasswordException ex) {
        return ResponseEntity.badRequest().body(ApiError.of(400, "Bad Request", ex.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(EmailAlreadyUsedException.class)
    public ResponseEntity<ApiError> emailTaken(EmailAlreadyUsedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(409, "Conflict", ex.getMessage()));
    }
}
