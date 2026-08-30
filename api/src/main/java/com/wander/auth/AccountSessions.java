package com.wander.auth;

import java.util.Map;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Ending somebody's sessions.
 *
 * Sessions live in Postgres and the session *is* the credential, so anything
 * that takes an account away — changing its password, disabling it — has to
 * reach into the store rather than hoping the browser cooperates. Spring Session
 * indexes by principal name, so both of these are a lookup rather than a scan of
 * every session on the instance.
 *
 * The two methods differ by exactly one session and the difference is not
 * cosmetic. Somebody changing their own password wants every *other* session
 * gone and their own kept — signing them out of the page they are looking at
 * reads as the change having failed. An administrator disabling an account wants
 * the lot, including whichever one is being used right now, because that is what
 * "out of service" means.
 */
@Component
public class AccountSessions {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public AccountSessions(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    /** Every session for this account except one — used when somebody changes their own password. */
    public void endOthers(String email, String keepSessionId) {
        Map<String, ? extends Session> found = sessions.findByPrincipalName(email);
        found.keySet().stream()
                .filter(id -> !id.equals(keepSessionId))
                .forEach(sessions::deleteById);
    }

    /** Every session for this account, with no exception. */
    public void endAll(String email) {
        sessions.findByPrincipalName(email).keySet().forEach(sessions::deleteById);
    }
}
