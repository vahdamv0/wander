package com.wander.admin;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wander.admin.dto.AdminUserView;
import com.wander.admin.dto.CreateResetRequest;
import com.wander.admin.dto.CreatedResetView;
import com.wander.admin.dto.PasswordResetView;
import com.wander.admin.dto.SetDisabledRequest;
import com.wander.security.WanderUser;

import jakarta.validation.Valid;

/**
 * Administration of the instance's accounts.
 *
 * **`@PreAuthorize` on the class, not a path rule in `SecurityConfig`.** Both
 * would work today; this one travels with the code. A matcher in the security
 * configuration is a second place to remember, and the failure mode when
 * somebody adds a method here and the matcher does not cover it is an
 * administrative endpoint open to every signed-in account —
 * {@code EndpointAuthRatchetTest} would not catch it, because it only asks
 * whether an *anonymous* caller gets in. Annotating the type means a new method
 * is guarded by existing.
 *
 * `WanderUser` already grants `ROLE_ADMIN` from {@code users.role}, which has
 * been written since V1 and, until now, checked nowhere.
 *
 * Operation ids are global on the generated client, so the methods are named for
 * what they act on: `listAccounts`, not `list`.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService admin;
    private final PasswordResetService resets;

    public AdminController(AdminService admin, PasswordResetService resets) {
        this.admin = admin;
        this.resets = resets;
    }

    @GetMapping("/accounts")
    public List<AdminUserView> listAccounts() {
        return admin.listAccounts();
    }

    /** Take an account out of service, or put it back. */
    @PutMapping("/accounts/{userId}/disabled")
    public AdminUserView setAccountDisabled(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long userId,
            @Valid @RequestBody SetDisabledRequest request) {
        return admin.setDisabled(principal.id(), userId, request.disabled());
    }

    /**
     * Mint a password reset link.
     *
     * The response carries the token, once. Everything after this call can only
     * see a digest, so a client that loses it has to revoke and mint another.
     */
    @PostMapping("/accounts/{userId}/resets")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedResetView createReset(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long userId,
            @Valid @RequestBody CreateResetRequest request) {
        return resets.create(principal.id(), userId, request);
    }

    /** What became of the links minted for this account — the answer to "did they use it". */
    @GetMapping("/accounts/{userId}/resets")
    public List<PasswordResetView> listResets(@PathVariable Long userId) {
        return resets.list(userId);
    }

    @DeleteMapping("/accounts/{userId}/resets/{resetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeReset(@PathVariable Long userId, @PathVariable Long resetId) {
        resets.revoke(userId, resetId);
    }
}
