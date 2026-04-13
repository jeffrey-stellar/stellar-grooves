package com.stellarideas.grooves.integration;

import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.repository.MusicFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.*;

class MusicFileRepositoryIT extends BaseIntegrationTest {

    @Autowired
    private MusicFileRepository musicFileRepository;

    @BeforeEach
    void cleanUp() {
        musicFileRepository.deleteAll();
    }

    @Test
    void searchByUserIdAndQueryFindsMatchingTracks() {
        MusicFile file = MusicFile.builder()
                .title("Master of Puppets")
                .artist("Metallica")
                .album("Master of Puppets")
                .genre(Genre.THRASH_METAL)
                .userId("user1")
                .filePath("/music/metallica/master.mp3")
                .fileName("master.mp3")
                .build();
        musicFileRepository.save(file);

        Page<MusicFile> results = musicFileRepository.searchByUserIdAndQuery(
                "user1", "metallica", PageRequest.of(0, 10));
        assertEquals(1, results.getTotalElements());
        assertEquals("Metallica", results.getContent().get(0).getArtist());
    }

    @Test
    void searchIsCaseInsensitive() {
        MusicFile file = MusicFile.builder()
                .title("Paranoid")
                .artist("Black Sabbath")
                .album("Paranoid")
                .genre(Genre.CLASSIC_ROCK)
                .userId("user1")
                .filePath("/music/sabbath/paranoid.mp3")
                .fileName("paranoid.mp3")
                .build();
        musicFileRepository.save(file);

        Page<MusicFile> results = musicFileRepository.searchByUserIdAndQuery(
                "user1", "paranoid", PageRequest.of(0, 10));
        assertEquals(1, results.getTotalElements());
    }

    @Test
    void searchDoesNotReturnOtherUsersFiles() {
        MusicFile file = MusicFile.builder()
                .title("Ace of Spades")
                .artist("Motorhead")
                .album("Ace of Spades")
                .genre(Genre.HARD_ROCK)
                .userId("user2")
                .filePath("/music/motorhead/ace.mp3")
                .fileName("ace.mp3")
                .build();
        musicFileRepository.save(file);

        Page<MusicFile> results = musicFileRepository.searchByUserIdAndQuery(
                "user1", "motorhead", PageRequest.of(0, 10));
        assertEquals(0, results.getTotalElements());
    }

    @Test
    void findDuplicatesDetectsMatchingTitleAndArtist() {
        MusicFile file1 = MusicFile.builder()
                .title("Iron Man").artist("Black Sabbath").genre(Genre.CLASSIC_ROCK)
                .userId("user1").filePath("/music/a.mp3").fileName("a.mp3").build();
        MusicFile file2 = MusicFile.builder()
                .title("iron man").artist("black sabbath").genre(Genre.CLASSIC_ROCK)
                .userId("user1").filePath("/music/b.mp3").fileName("b.mp3").build();
        musicFileRepository.save(file1);
        musicFileRepository.save(file2);

        var duplicates = musicFileRepository.findDuplicatesByUserId("user1");
        assertEquals(1, duplicates.size());
    }
}
