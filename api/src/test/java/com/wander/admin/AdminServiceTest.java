package com.wander.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.wander.auth.AccountSessions;
import com.wander.common.ConflictException;
import com.wander.user.GlobalRole;
import com.wander.user.User;
import com.wander.user.UserRepository;

/**
 * The one rule about administrators that a database cannot be asked about
 * honestly: an instance must never be left without one who can sign in.
 *
 * It is an **instance-wide count**, and the integration suite shares one
 * database that every other test adds administrators to — so over HTTP the
 * "last" administrator can be arranged only by accident, and a test that
 * asserted it would be asserting on whatever else had run first. Here the count
 * is whatever this test says it is.
 *
 * This is the "last owner" check {@code TripMemberService} is pleased not to
 * need, and the difference is the interesting part. A trip's ownership *moves* —
 * one transaction demotes the caller and promotes the target — so there is never
 * a moment with no owner and no count to get wrong. Administrators are a set,
 * there is no equivalent atomic move, and this count is the only thing standing
 * between a demotion and an instance nobody can administer.
 */
class AdminServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final AccountSessions sessions = mock(AccountSessions.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AdminService service = new AdminService(users, sessions, events);

    private User account(long id, GlobalRole role, boolean disabled) {
        User user = new User("person%d@example.com".formatted(id), "Person " + id, "{bcrypt}x", role);
        if (disabled) {
            user.disable(Instant.now());
        }
        when(users.findById(id)).thenReturn(Optional.of(user));
        return user;
    }

    private void enabledAdminsOnTheInstance(long count) {
        when(users.countByRoleAndDisabledAtIsNull(GlobalRole.ADMIN)).thenReturn(count);
    }

    @Test
    void theOnlyAdministratorCannotStepDown() {
        User onlyAdmin = account(1, GlobalRole.ADMIN, false);
        enabledAdminsOnTheInstance(1);

        assertThatThrownBy(() -> service.setRole(1L, GlobalRole.USER))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("only administrator");

        // Refused means unchanged, and no sessions ended — a rejected call that
        // signed somebody out would be the worst of both.
        assertThat(onlyAdmin.getRole()).isEqualTo(GlobalRole.ADMIN);
        verify(sessions, never()).endAll(anyString());
    }

    @Test
    void anAdministratorMayStepDownWhenAnotherOneCanSignIn() {
        User admin = account(1, GlobalRole.ADMIN, false);
        enabledAdminsOnTheInstance(2);

        assertThatCode(() -> service.setRole(1L, GlobalRole.USER)).doesNotThrowAnyException();

        assertThat(admin.getRole()).isEqualTo(GlobalRole.USER);
        // Their own session included: somebody who has given up their authority
        // should not keep a page that still offers it.
        verify(sessions).endAll(admin.getEmail());
    }

    @Test
    void aDisabledAdministratorDoesNotCountAsSomebodyInCharge() {
        User admin = account(1, GlobalRole.ADMIN, false);
        // Two ADMIN rows on the instance, but only this one is enabled — the
        // other cannot sign in, so it cannot administer anything. Counting it
        // would allow the same lockout by a longer route, which is why the query
        // is countByRoleAndDisabledAtIsNull rather than countByRole.
        enabledAdminsOnTheInstance(1);

        assertThatThrownBy(() -> service.setRole(1L, GlobalRole.USER))
                .isInstanceOf(ConflictException.class);
        assertThat(admin.getRole()).isEqualTo(GlobalRole.ADMIN);
    }

    @Test
    void promotingIsNeverRefusedByTheCount() {
        User user = account(1, GlobalRole.USER, false);
        enabledAdminsOnTheInstance(1);

        assertThatCode(() -> service.setRole(1L, GlobalRole.ADMIN)).doesNotThrowAnyException();

        assertThat(user.getRole()).isEqualTo(GlobalRole.ADMIN);
        // Promotion ends sessions too, so the new administrator's next sign-in
        // carries the role — the principal is serialised into the session, so
        // otherwise their menu would disagree with their authority.
        verify(sessions).endAll(user.getEmail());
    }

    @Test
    void settingTheRoleAnAccountAlreadyHasEndsNothing() {
        User user = account(1, GlobalRole.USER, false);

        assertThatCode(() -> service.setRole(1L, GlobalRole.USER)).doesNotThrowAnyException();

        assertThat(user.getRole()).isEqualTo(GlobalRole.USER);
        // No count was even consulted, and nobody was signed out to confirm a
        // no-op.
        verify(users, never()).countByRoleAndDisabledAtIsNull(any());
        verify(sessions, never()).endAll(anyString());
    }
}
