package ru.postscriptum.portal.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.postscriptum.portal.model.User;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
