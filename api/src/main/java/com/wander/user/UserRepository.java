package com.wander.user;

import java.util.List;
import java.util.Optional;


import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    /** The administrator's list of accounts. Newest first, because that is who a question is usually about. */
    List<User> findAllByOrderByCreatedAtDesc();

    /**
     * How many administrators this instance has, ignoring disabled accounts.
     *
     * This is the "last owner" check {@code TripMemberService} is pleased not to
     * need, and the difference is worth knowing. A trip's ownership *moves* — one
     * transaction demotes the caller and promotes the target — so there is never
     * a moment with no owner and never a count to get wrong. An instance's
     * administrators are a set rather than a role one person holds, so there is
     * no equivalent atomic move and this is the only thing standing between a
     * demotion and an instance nobody can administer.
     *
     * Disabled admins do not count: an account that cannot sign in cannot
     * administer anything, so leaving one as the sole administrator is the same
     * lockout by a longer route.
     */
    long countByRoleAndDisabledAtIsNull(GlobalRole role);

    boolean existsByEmailIgnoreCase(String email);
}
