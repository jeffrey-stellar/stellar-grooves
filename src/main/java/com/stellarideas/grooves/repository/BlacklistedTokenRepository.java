package com.stellarideas.grooves.repository;

import com.stellarideas.grooves.model.BlacklistedToken;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BlacklistedTokenRepository extends MongoRepository<BlacklistedToken, String> {
    boolean existsByJti(String jti);
}
