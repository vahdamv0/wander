package com.wander.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wander.admin.dto.RedeemResetRequest;
import com.wander.admin.dto.ResetPreview;
import com.wander.auth.LoginThrottle;
import com.wander.common.NotFoundException;
import com.wander.common.PublicEndpoint;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Redeeming a password reset link. The public half of the feature.
 *
 * **This is the second anonymous endpoint in this application, and it is one on
 * purpose.** `/api/config/sign-in` was documented as the only one, and the
 * reasoning there — an authenticated preview costs one extra step and keeps the
 * default-deny surface at exactly one hole — was right for invitations, where
 * the recipient can always register first. It does not survive contact with this
 * feature: the entire audience for a reset link is somebody who *cannot sign
 * in*. An authenticated reset would be a door that only opens for people who do
 * not need it.
 *
 * What keeps that affordable is that the token is the whole credential and it is
 * a good one — 256 bits from a CSPRNG, hashed at rest, single use, expiring, and
 * revocable — so this endpoint gives an anonymous caller nothing they did not
 * already hold. A token that does not exist is a 404 whatever is wrong with it,
 * so it cannot be walked to discover which links are live.
 *
 * It is throttled by address like the other anonymous doors, for the reason
 * spelled out in {@code LoginThrottle}: the redeem call spends a bcrypt round
 * encoding the new password, and an unmetered endpoint that hashes on behalf of
 * an anonymous caller is a way to burn this box's CPU whatever it is guarding.
 */
@RestController
@RequestMapping("/api/auth/reset")
public class PasswordResetController {

    private final PasswordResetService resets;
    private final LoginThrottle throttle;

    public PasswordResetController(PasswordResetService resets, LoginThrottle throttle) {
        this.resets = resets;
        this.throttle = throttle;
    }

    /** What the link says before you commit to it. */
    @PublicEndpoint
    @GetMapping("/{token}")
    public ResetPreview previewReset(@PathVariable String token, HttpServletRequest httpRequest) {
        String address = clientAddress(httpRequest);
        throttle.checkReset(address);
        try {
            ResetPreview preview = resets.preview(token);
            // Not cleared on success: a preview proves the caller holds a real
            // token, but redeeming it is a separate call and the counter is what
            // stands between this endpoint and being walked.
            return preview;
        } catch (NotFoundException ex) {
            throttle.resetFailed(address);
            throw ex;
        }
    }

    /**
     * Set the password and burn the link.
     *
     * 204, and the caller is deliberately *not* signed in by it — unlike
     * registration, which does log you straight in. Somebody who has just been
     * handed a link by an administrator should end up at a sign-in form typing
     * the password they chose, because that is what proves it took. Signing them
     * in here would also mean issuing a session to whoever holds the link, which
     * is a strictly larger thing than letting them set a password.
     */
    @PublicEndpoint
    @PostMapping("/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void redeemReset(@PathVariable String token, @Valid @RequestBody RedeemResetRequest request,
            HttpServletRequest httpRequest) {
        String address = clientAddress(httpRequest);
        throttle.checkReset(address);
        try {
            resets.redeem(token, request.newPassword());
        } catch (NotFoundException ex) {
            throttle.resetFailed(address);
            throw ex;
        }
        throttle.resetSucceeded(address);
    }

    /**
     * See {@code AuthController.clientAddress}: this is the client's address only
     * because the proxy overwrites `X-Forwarded-For`, and the Caddyfile's
     * `header_up X-Forwarded-For {remote_host}` is what makes that true.
     */
    private static String clientAddress(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null ? "" : address;
    }
}
