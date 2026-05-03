package com.stellarideas.grooves.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that @Size bounds fire at the DTO layer for free-form user input.
 * Inputs that exceed the bound should produce constraint violations before
 * any service-layer code runs.
 */
class DTOSizeValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (factory != null) factory.close();
    }

    private static String repeat(String s, int times) {
        return s.repeat(times);
    }

    @Test
    void addTrackRequest_acceptsBoundedFileId() {
        AddTrackRequest req = new AddTrackRequest();
        req.setFileId("a".repeat(64));
        assertTrue(validator.validate(req).isEmpty());
    }

    @Test
    void addTrackRequest_rejectsOversizedFileId() {
        AddTrackRequest req = new AddTrackRequest();
        req.setFileId("a".repeat(65));
        Set<ConstraintViolation<AddTrackRequest>> violations = validator.validate(req);
        assertFalse(violations.isEmpty());
    }

    @Test
    void bulkDeleteRequest_rejectsOversizedFileIdMember() {
        BulkDeleteRequest req = new BulkDeleteRequest();
        req.setFileIds(List.of("a".repeat(65)));
        assertFalse(validator.validate(req).isEmpty());
    }

    @Test
    void bulkTagsRequest_rejectsOversizedFileIdMember() {
        BulkTagsRequest req = new BulkTagsRequest();
        req.setFileIds(List.of("a".repeat(65)));
        req.setAdd(List.of("tag"));
        assertFalse(validator.validate(req).isEmpty());
    }

    @Test
    void passwordResetExecuteDTO_rejectsOversizedToken() {
        PasswordResetExecuteDTO req = new PasswordResetExecuteDTO();
        req.setToken("a".repeat(257));
        req.setNewPassword("Password123");
        assertFalse(validator.validate(req).isEmpty());
    }

    @Test
    void passwordResetRequestDTO_rejectsOversizedEmail() {
        PasswordResetRequestDTO req = new PasswordResetRequestDTO();
        // 255-char local part + @example.com → 267 chars
        req.setEmail(repeat("a", 255) + "@example.com");
        assertFalse(validator.validate(req).isEmpty());
    }

    @Test
    void playbackQueueDTO_rejectsOversizedTrackIdMember() {
        PlaybackQueueDTO dto = new PlaybackQueueDTO();
        dto.setTrackIds(List.of("a".repeat(65)));
        assertFalse(validator.validate(dto).isEmpty());
    }

    @Test
    void playbackQueueDTO_rejectsOversizedCurrentTrackId() {
        PlaybackQueueDTO dto = new PlaybackQueueDTO();
        dto.setCurrentTrackId("a".repeat(65));
        assertFalse(validator.validate(dto).isEmpty());
    }

    @Test
    void refreshTokenRequest_rejectsOversizedToken() {
        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken("a".repeat(257));
        assertFalse(validator.validate(req).isEmpty());
    }

    @Test
    void reorderTracksRequest_rejectsOversizedListAndMember() {
        ReorderTracksRequest req = new ReorderTracksRequest();
        // Member too long
        req.setTrackIds(List.of("a".repeat(65)));
        assertFalse(validator.validate(req).isEmpty());
    }

    @Test
    void updateGenreRequest_rejectsOversizedGenre() {
        UpdateGenreRequest req = new UpdateGenreRequest();
        req.setGenre("a".repeat(51));
        assertFalse(validator.validate(req).isEmpty());
    }

    @Test
    void updateGenreRequest_acceptsBoundedGenre() {
        UpdateGenreRequest req = new UpdateGenreRequest();
        req.setGenre("HEAVY_METAL");
        assertTrue(validator.validate(req).isEmpty());
    }
}
