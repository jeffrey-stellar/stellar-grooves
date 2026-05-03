package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.dto.SmartPlaylistPhraseDTO;
import com.stellarideas.grooves.dto.SmartPlaylistPhraseRequest;
import com.stellarideas.grooves.model.SmartPlaylistPhrase;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.SmartPlaylistPhraseRepository;
import com.stellarideas.grooves.security.CurrentUser;
import com.stellarideas.grooves.smartplaylist.ParsedQuery;
import com.stellarideas.grooves.smartplaylist.QueryParseException;
import com.stellarideas.grooves.smartplaylist.SmartPlaylistQueryParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * CRUD for the user's phrase library — named query fragments referenced from
 * smart-playlist queries via {@code @phrase}. References are resolved at
 * execution time; this controller only manages definitions.
 *
 * <p>Phrase bodies are validated <em>structurally</em> at create/update time:
 * they must parse as a non-empty expression with no top-level {@code sort:} /
 * {@code limit:} clauses. Whether the body's {@code @references} actually
 * resolve is checked on use, not here — letting curators iterate without
 * strict dependency ordering.
 */
@RestController
@RequestMapping("/api/v1/smart-playlists/phrases")
@Tag(name = "Smart Playlist Phrases",
        description = "Reusable named query fragments (@jazz-core) for smart playlists")
public class SmartPlaylistPhraseController {

    private final SmartPlaylistPhraseRepository repository;
    private final SmartPlaylistQueryParser parser;

    public SmartPlaylistPhraseController(SmartPlaylistPhraseRepository repository,
                                         SmartPlaylistQueryParser parser) {
        this.repository = repository;
        this.parser = parser;
    }

    @Operation(summary = "List phrases", description = "All phrases owned by the current user, alphabetical by name")
    @GetMapping
    public List<SmartPlaylistPhraseDTO> list(@CurrentUser User user) {
        return repository.findByUserIdOrderByNameAsc(user.getId()).stream()
                .map(SmartPlaylistPhraseDTO::from)
                .collect(Collectors.toList());
    }

    @Operation(summary = "Create phrase")
    @PostMapping
    public ResponseEntity<?> create(@CurrentUser User user, @Valid @RequestBody SmartPlaylistPhraseRequest body) {
        String name = body.getName().toLowerCase(Locale.ROOT);
        try {
            validateBody(name, body.getBody());
        } catch (QueryParseException e) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, e.getMessage()));
        }
        if (repository.existsByUserIdAndName(user.getId(), name)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(GlobalExceptionHandler.problem(HttpStatus.CONFLICT,
                            "A phrase named @" + name + " already exists"));
        }
        SmartPlaylistPhrase p = new SmartPlaylistPhrase();
        p.setUserId(user.getId());
        p.setName(name);
        p.setBody(body.getBody());
        p.setDescription(body.getDescription());
        SmartPlaylistPhrase saved = repository.save(p);
        return ResponseEntity.ok(SmartPlaylistPhraseDTO.from(saved));
    }

    @Operation(summary = "Update phrase")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@CurrentUser User user, @PathVariable String id,
                                    @Valid @RequestBody SmartPlaylistPhraseRequest body) {
        Optional<SmartPlaylistPhrase> existing = repository.findByIdAndUserId(id, user.getId());
        if (existing.isEmpty()) return ResponseEntity.notFound().build();
        String name = body.getName().toLowerCase(Locale.ROOT);
        try {
            validateBody(name, body.getBody());
        } catch (QueryParseException e) {
            return ResponseEntity.badRequest()
                    .body(GlobalExceptionHandler.problem(HttpStatus.BAD_REQUEST, e.getMessage()));
        }
        SmartPlaylistPhrase p = existing.get();
        if (!p.getName().equals(name) && repository.existsByUserIdAndName(user.getId(), name)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(GlobalExceptionHandler.problem(HttpStatus.CONFLICT,
                            "A phrase named @" + name + " already exists"));
        }
        p.setName(name);
        p.setBody(body.getBody());
        p.setDescription(body.getDescription());
        return ResponseEntity.ok(SmartPlaylistPhraseDTO.from(repository.save(p)));
    }

    @Operation(summary = "Delete phrase")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@CurrentUser User user, @PathVariable String id) {
        Optional<SmartPlaylistPhrase> existing = repository.findByIdAndUserId(id, user.getId());
        if (existing.isEmpty()) return ResponseEntity.notFound().build();
        repository.delete(existing.get());
        return ResponseEntity.ok(Map.of("message", "Phrase deleted"));
    }

    /**
     * Validate phrase body. Allowed: any expression that parses as a non-empty
     * fragment. Disallowed: top-level {@code sort:} / {@code limit:} (those are
     * the smart playlist's job, not the phrase's). References to other phrases
     * are <em>not</em> validated here — that's deferred to execution time.
     */
    private void validateBody(String name, String body) {
        ParsedQuery parsed = parser.parse(body);
        if (parsed.expression().isEmpty()) {
            throw new QueryParseException("Phrase @" + name + " has an empty body");
        }
        if (parsed.sort().isPresent() || parsed.limit().isPresent()) {
            throw new QueryParseException(
                    "Phrase @" + name + " cannot contain sort: or limit: — those belong on the smart playlist");
        }
    }
}
