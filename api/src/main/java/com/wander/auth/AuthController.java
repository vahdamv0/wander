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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wander.auth.dto.LoginRequest;
import com.wander.auth.dto.RegisterRequest;
import com.wander.auth.dto.SessionUser;
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

    public AuthController(AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository, UserAccountService accounts,
            LoginThrottle throttle) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.accounts = accounts;
        this.throttle = throttle;
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

    @org.springframework.web.bind.annotation.ExceptionHandler(EmailAlreadyUsedException.class)
    public ResponseEntity<ApiError> emailTaken(EmailAlreadyUsedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(409, "Conflict", ex.getMessage()));
    }
}
