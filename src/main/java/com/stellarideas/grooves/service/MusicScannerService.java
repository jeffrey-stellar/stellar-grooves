package com.stellarideas.grooves.service;

import com.stellarideas.grooves.model.Genre;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.MusicFileRepository;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MusicScannerService {

    @Autowired
    private MusicCatalogService catalogService;

    @Autowired
    private MusicFileRepository repository;

    public void scanDirectory(User user, String directoryPath) throws Exception {
        Path root = Paths.get(directoryPath);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("Invalid directory path");
        }

        List<Path> musicFilesPaths = Files.walk(root)
                .filter(p -> p.toString().toLowerCase().endsWith(".mp3") || 
                             p.toString().toLowerCase().endsWith(".m4a") || 
                             p.toString().toLowerCase().endsWith(".flac"))
                .collect(Collectors.toList());

        for (Path path : musicFilesPaths) {
            try {
                AudioFile f = AudioFileIO.read(path.toFile());
                Tag tag = f.getTag();
                
                String artist = tag.getFirst(FieldKey.ARTIST);
                String album = tag.getFirst(FieldKey.ALBUM);
                String title = tag.getFirst(FieldKey.TITLE);
                String year = tag.getFirst(FieldKey.YEAR);

                Set<Genre> genres = catalogService.identifyGenres(artist);
                Genre primaryGenre = genres.isEmpty() ? Genre.OTHER : genres.iterator().next();
                boolean multiGenre = genres.size() > 1;

                MusicFile musicFile = MusicFile.builder()
                        .user(user)
                        .filePath(path.toString())
                        .fileName(path.getFileName().toString())
                        .artist(artist)
                        .album(album)
                        .title(title)
                        .year(year)
                        .primaryGenre(primaryGenre)
                        .multiGenre(multiGenre)
                        .build();

                repository.save(musicFile);
            } catch (Exception e) {
                // Log and continue with next file
            }
        }
    }
}
