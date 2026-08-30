package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.wander.auth.UserAccountService;
import com.wander.user.GlobalRole;

/**
 * A closed instance that can still be joined by invitation.
 *
 * This is the pair of rules that make "registration disabled" a *closed* instance
 * rather than a sealed one, and they only make sense together: sign-ups are
 * refused, and a live invitation link admits its holder anyway. Without the
 * second, turning the switch off would quietly break every invitation — the link
 * needs an account, accepting one is authenticated, and nothing here sends mail,
 * so there would be no way for anybody but the first-boot admin ever to join.
 *
 * Its own context, because the switch is read at configuration time. The owner is
 * created through UserAccountService directly rather than by registering, for the
 * obvious reason: on this instance registering is the thing under test.
 */
@TestPropertySource(properties = "wander.registration-enabled=false")
class InviteRegistrationIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private UserAccountService accounts;

    /** An account made the way an operator makes one: not through the sign-up form. */
    private Session anOwner() {
        String email = "closed-owner-%s@example.com".formatted(unique());
        accounts.create(email, "Closed Owner", PASSWORD, GlobalRole.USER);
        return login(email, PASSWORD);
    }

    private ResponseEntity<String> attemptRegister(String email, String inviteTokenJson) {
        String csrf = bootstrapCsrf();
        return http().post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf)
                .body("""
                        {"email":"%s","displayName":"Invited","password":"%s"%s}
                        """.formatted(email, PASSWORD, inviteTokenJson))
                .retrieve()
                .toEntity(String.class);
    }

    @Test
    void aLinkIsEnoughToSignUpOnAnInstanceThatOtherwiseRefuses() {
        Session owner = anOwner();
        Object tripId = asMap(post(owner, "/api/trips", """
                {"name":"Porto","startDate":"2027-05-01","endDate":"2027-05-04","currency":"EUR"}
                """).getBody()).get("id");
        String token = (String) asMap(post(owner, "/api/trips/" + tripId + "/invites", """
                {"role":"EDITOR"}
                """).getBody()).get("token");

        String email = "invited-%s@example.com".formatted(unique());
        ResponseEntity<String> registered = attemptRegister(email, ",\"inviteToken\":\"%s\"".formatted(token));
        assertThat(registered.getStatusCode().value()).isEqualTo(201);

        // And the journey finishes where it was going: the account that the link
        // created uses the same link to join. It was checked at registration, not
        // spent there.
        Session invited = login(email, PASSWORD);
        assertThat(asMap(get(invited, "/api/invites/" + token).getBody()).get("joinable")).isEqualTo(true);
        assertThat(asMap(post(invited, "/api/invites/" + token + "/accept", "").getBody()).get("tripId"))
                .isEqualTo(tripId);
        assertThat(asList(get(owner, "/api/trips/" + tripId + "/members").getBody())).hasSize(2);
    }

    @Test
    void withoutOneTheSignUpIsStillRefused() {
        assertThat(attemptRegister("uninvited-%s@example.com".formatted(unique()), "").getStatusCode().value())
                .isEqualTo(403);
    }

    @Test
    void aMadeUpTokenIsTheSameAnswerAsNoTokenAtAll() {
        // Same 403, so the register endpoint cannot be used to find out which
        // invitations exist — the same reasoning as a bad token being a 404 at
        // the preview endpoint rather than a more helpful message.
        assertThat(attemptRegister("guesser-%s@example.com".formatted(unique()),
                ",\"inviteToken\":\"nBqXk3rL9tYwZ2vC5mJdH8pQ4sT7gF1aE6uR0iO3xN\"").getStatusCode().value())
                .isEqualTo(403);
    }

    @Test
    void aSpentLinkDoesNotAdmitASecondAccount() {
        Session owner = anOwner();
        Object tripId = asMap(post(owner, "/api/trips", """
                {"name":"Braga","startDate":"2027-06-01","endDate":"2027-06-03","currency":"EUR"}
                """).getBody()).get("id");
        String token = (String) asMap(post(owner, "/api/trips/" + tripId + "/invites", """
                {"role":"VIEWER"}
                """).getBody()).get("token");

        String first = "first-%s@example.com".formatted(unique());
        String tokenJson = ",\"inviteToken\":\"%s\"".formatted(token);
        assertThat(attemptRegister(first, tokenJson).getStatusCode().value()).isEqualTo(201);
        post(login(first, PASSWORD), "/api/invites/" + token + "/accept", "");

        // A forwarded link is a credential that has been used. It stopped being a
        // way onto the trip, so it stops being a way onto the instance.
        assertThat(attemptRegister("second-%s@example.com".formatted(unique()), tokenJson).getStatusCode().value())
                .isEqualTo(403);
    }

    @Test
    void aRevokedLinkAdmitsNobody() {
        Session owner = anOwner();
        Object tripId = asMap(post(owner, "/api/trips", """
                {"name":"Faro","startDate":"2027-07-01","endDate":"2027-07-03","currency":"EUR"}
                """).getBody()).get("id");
        Map<String, Object> created = asMap(post(owner, "/api/trips/" + tripId + "/invites", """
                {"role":"VIEWER"}
                """).getBody());
        String token = (String) created.get("token");
        @SuppressWarnings("unchecked")
        Object inviteId = ((Map<String, Object>) created.get("invite")).get("id");
        delete(owner, "/api/trips/" + tripId + "/invites/" + inviteId);

        assertThat(attemptRegister("revoked-%s@example.com".formatted(unique()),
                ",\"inviteToken\":\"%s\"".formatted(token)).getStatusCode().value()).isEqualTo(403);
    }
}
