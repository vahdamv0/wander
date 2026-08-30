package com.wander.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    /** The administrator's list of accounts. Newest first, because that is who a question is usually about. */
    List<User> findAllByOrderByCreatedAtDesc();

    boolean existsByEmailIgnoreCase(String email);
}
