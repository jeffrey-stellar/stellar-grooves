package com.stellarideas.grooves.repository;

import com.stellarideas.grooves.model.Role;
import com.stellarideas.grooves.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByUsername(String username);
    Boolean existsByUsername(String username);
    Boolean existsByUsernameIgnoreCase(String username);
    Boolean existsByEmail(String email);
    Boolean existsByEmailIgnoreCase(String email);
    boolean existsByRolesContaining(Role role);
    Optional<User> findByEmail(String email);

    List<User> findByScanScheduleNotNull();
}
