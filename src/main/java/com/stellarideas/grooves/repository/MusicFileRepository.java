package com.stellarideas.grooves.repository;

import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface MusicFileRepository extends MongoRepository<MusicFile, String> {
    List<MusicFile> findByUser(User user);
}
