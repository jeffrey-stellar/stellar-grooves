package com.stellarideas.grooves.repository;

import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;
import java.util.Optional;

public interface MusicFileRepository extends MongoRepository<MusicFile, String> {
    List<MusicFile> findByUser(User user);
    Page<MusicFile> findByUser(User user, Pageable pageable);
    List<MusicFile> findByUserAndGenre(User user, Genre genre);
    Page<MusicFile> findByUserAndGenre(User user, Genre genre, Pageable pageable);
    boolean existsByFilePathAndUser(String filePath, User user);
    boolean existsByTitleAndArtistAndUser(String title, String artist, User user);
    Optional<MusicFile> findByIdAndUser(String id, User user);
    List<MusicFile> findByIdInAndUser(List<String> ids, User user);
}
