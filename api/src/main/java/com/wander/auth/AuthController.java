package com.wander.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
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

    public AuthController(AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository, UserAccountService accounts) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.accounts = accounts;
    }

    @PublicEndpoint
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionUser register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        User created = accounts.register(request.email(), request.displayName(), request.password());
        // Log the new account straight in — a register call that then makes the
        // client POST /login separately is two round trips for no gain.
        authenticate(created.getEmail(), request.password(), httpRequest, httpResponse);
        return new SessionUser(created.getId(), created.getEmail(), created.getDisplayName(), created.getRole());
    }

    @PublicEndpoint
    @PostMapping("/login")
    public SessionUser login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        Authentication authentication = authenticate(request.email(), request.password(), httpRequest, httpResponse);
        return SessionUser.from((WanderUser) authentication.getPrincipal());
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

    private Authentication authenticate(String email, String password, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        Authentication authentication = authenticationManager
                .authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));

        // Rotate the session id on login so an id an attacker planted beforehand
        // cannot be ridden afterwards. Only when a session already exists:
        // changeSessionId() throws outright if there is none, and a fresh client
        // (no JSESSIONID yet) gets a brand-new id from saveContext below anyway,
        // which is the same protection by a different route.
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
