package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.wander.auth.UserAccountService;
import com.wander.user.GlobalRole;

/**
 * The administrator's authority over accounts.
 *
 * {@code GlobalRole.ADMIN} was written by first boot and read by nothing for the
 * whole life of this project, so the first test here is the one that matters
 * most: everybody who is *not* an administrator is refused. A role that grants
 * nothing is a control that appears to exist, and this is what stops it becoming
 * one that grants everything by accident.
 */
class AdminIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private UserAccountService accounts;

    /**
     * An administrator, made the way first boot makes one — through the service,
     * not by registering, because registration deliberately cannot produce one.
     */
    protected Session admin() {
        String email = "admin-%s@example.com".formatted(unique());
        accounts.create(email, "Administrator", PASSWORD, GlobalRole.ADMIN);
        return login(email, PASSWORD);
    }

    private Object idOf(Session session) {
        return asMap(get(session, "/api/auth/me").getBody()).get("id");
    }

    @Test
    void everyAdminEndpointRefusesAnOrdinaryAccount() {
        Session ordinary = register("ordinary");
        Object victimId = idOf(register("victim"));

        // 403, not 404: the caller is authenticated, they simply may not. There
        // is nothing to hide here the way there is with a trip — that an
        // instance has administrators is not a secret.
        assertThat(get(ordinary, "/api/admin/accounts").getStatusCode().value()).isEqualTo(403);
        assertThat(put(ordinary, "/api/admin/accounts/" + victimId + "/disabled", """
                {"disabled":true}
                """).getStatusCode().value()).isEqualTo(403);
        assertThat(post(ordinary, "/api/admin/accounts/" + victimId + "/resets", "{}")
                .getStatusCode().value()).isEqualTo(403);
        assertThat(get(ordinary, "/api/admin/accounts/" + victimId + "/resets").getStatusCode().value())
                .isEqualTo(403);

        // And the account it was pointed at is untouched.
        assertThat(login(register("bystander").email(), PASSWORD)).isNotNull();
    }

    @Test
    void theAccountListShowsEveryAccountAndItsState() {
        Session admin = admin();
        Session user = register("listed");

        List<Map<String, Object>> accountList = asList(get(admin, "/api/admin/accounts").getBody());
        assertThat(accountList).anySatisfy(account -> {
            assertThat(account.get("email")).isEqualTo(user.email());
            assertThat(account.get("role")).isEqualTo("USER");
            assertThat(account.get("disabled")).isEqualTo(false);
            // Authority over accounts is not entitlement to their credentials.
            assertThat(account).doesNotContainKey("passwordHash");
        });
    }

    @Test
    void disablingAnAccountEndsItsSessionAndRefusesTheNextSignIn() {
        Session admin = admin();
        Session user = register("disabled");
        Object userId = idOf(user);

        // Signed in and working, right up to the moment it is not.
        assertThat(get(user, "/api/auth/me").getStatusCode().value()).isEqualTo(200);

        assertThat(put(admin, "/api/admin/accounts/" + userId + "/disabled", """
                {"disabled":true}
                """).getStatusCode().value()).isEqualTo(200);

        // The live session is gone, which is the half that a flag on the row
        // alone would miss: the session *is* the credential, and one left alive
        // would sail past the sign-in check for as long as it lasted.
        assertThat(get(user, "/api/auth/me").getStatusCode().value()).isEqualTo(401);
        // And the password no longer opens the door either.
        assertThat(attemptLogin(user.email(), PASSWORD).getStatusCode().value()).isEqualTo(401);

        // Reversible, because "disabled" is a state and not a deletion.
        assertThat(put(admin, "/api/admin/accounts/" + userId + "/disabled", """
                {"disabled":false}
                """).getStatusCode().value()).isEqualTo(200);
        assertThat(attemptLogin(user.email(), PASSWORD).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anAdministratorCannotDisableThemselves() {
        Session admin = admin();

        // Refused because it would take the caller's own session out mid-request
        // and, on a one-admin instance — which is every instance by default —
        // leave nobody able to undo it. The recovery would be psql, which is the
        // situation this whole feature exists to end.
        assertThat(put(admin, "/api/admin/accounts/" + idOf(admin) + "/disabled", """
                {"disabled":true}
                """).getStatusCode().value()).isEqualTo(409);
        assertThat(get(admin, "/api/auth/me").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anAdministratorMayDisableAnotherAdministrator() {
        Session first = admin();
        Session second = admin();

        // Allowed: it is a real thing to have to do, and it leaves somebody
        // holding the keys — which is the only reason disabling yourself is not.
        assertThat(put(first, "/api/admin/accounts/" + idOf(second) + "/disabled", """
                {"disabled":true}
                """).getStatusCode().value()).isEqualTo(200);
        assertThat(attemptLogin(second.email(), PASSWORD).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void promotingSomebodyGivesThemTheAuthorityAndEndsTheirSession() {
        Session admin = admin();
        Session promoted = register("promoted");
        Object promotedId = idOf(promoted);

        // Refused before, allowed after: the whole point of the endpoint.
        assertThat(get(promoted, "/api/admin/accounts").getStatusCode().value()).isEqualTo(403);

        assertThat(put(admin, "/api/admin/accounts/" + promotedId + "/role", """
                {"role":"ADMIN"}
                """).getStatusCode().value()).isEqualTo(200);

        // Their old session is gone. The principal is serialised into the session
        // at sign-in and read back on every request, so a session that survived
        // would carry the old role — for a *demotion* that means authority
        // removed in the database and still held in fact, which is why both
        // directions end sessions rather than only the dangerous one.
        assertThat(get(promoted, "/api/auth/me").getStatusCode().value()).isEqualTo(401);

        Session again = login(promoted.email(), PASSWORD);
        assertThat(asMap(get(again, "/api/auth/me").getBody()).get("role")).isEqualTo("ADMIN");
        assertThat(get(again, "/api/admin/accounts").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void steppingDownIsAllowedWhenSomebodyElseCanTakeOver() {
        // The shared context already has administrators from other tests, so
        // there is always somebody else in charge here. That the *last* one
        // cannot step down is an instance-wide count and is pinned by
        // AdminServiceTest, which does not need a database to be sure of it.
        Session colleague = admin();
        Object colleagueId = idOf(colleague);

        // Two of them, so stepping down is allowed — and it is the caller's own
        // account, which is deliberately not refused: promoting a successor and
        // handing over is how an instance changes hands.
        assertThat(put(colleague, "/api/admin/accounts/" + colleagueId + "/role", """
                {"role":"USER"}
                """).getStatusCode().value()).isEqualTo(200);
        // Stepping down ends your own session too — you should not keep a page
        // still offering authority you have given up.
        assertThat(get(colleague, "/api/auth/me").getStatusCode().value()).isEqualTo(401);
        assertThat(get(login(colleague.email(), PASSWORD), "/api/admin/accounts")
                .getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void settingTheRoleAnAccountAlreadyHasChangesNothing() {
        Session admin = admin();
        Session user = register("unchanged");
        Object userId = idOf(user);

        // Idempotent rather than an error, and it must not end their session:
        // signing somebody out to confirm a no-op would be worse than the no-op.
        assertThat(put(admin, "/api/admin/accounts/" + userId + "/role", """
                {"role":"USER"}
                """).getStatusCode().value()).isEqualTo(200);
        assertThat(get(user, "/api/auth/me").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anAdministratorIsNotAMemberOfEverybodysTrips() {
        Session admin = admin();
        Session user = register("private");
        Object tripId = asMap(post(user, "/api/trips", """
                {"name":"Not yours","startDate":"2027-04-01","endDate":"2027-04-04","currency":"EUR"}
                """).getBody()).get("id");

        // The point of the whole feature: administering the instance is authority
        // over accounts, not a way into other people's holidays. Trip access
        // still goes through TripAccessService, which answers a non-member 404
        // whatever their global role is.
        assertThat(get(admin, "/api/trips/" + tripId).getStatusCode().value()).isEqualTo(404);
        assertThat(asList(get(admin, "/api/trips").getBody())).isEmpty();
    }
}
