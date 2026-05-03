package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.config.PaginationDefaults;
import com.stellarideas.grooves.model.User;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.executable.ExecutableValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Verifies controller pagination parameters carry @Min/@Max constraints. We use
 * ExecutableValidator (not a Spring context) to keep tests fast and focused on
 * constraint metadata. Instances are mocked since ExecutableValidator does not
 * invoke the methods — it only reads parameter metadata.
 *
 * In production, @Validated + MethodValidationPostProcessor wire these into a
 * Spring proxy so Hibernate Validator runs them on each invocation.
 */
class PaginationBoundsTest {

    private static jakarta.validation.ValidatorFactory factory;
    private static ExecutableValidator executableValidator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        executableValidator = factory.getValidator().forExecutables();
    }

    @AfterAll
    static void close() {
        if (factory != null) factory.close();
    }

    private static Method method(Class<?> cls, String name, Class<?>... params) throws NoSuchMethodException {
        return cls.getMethod(name, params);
    }

    @Test
    void libraryController_getFiles_rejectsOversizedSize() throws Exception {
        Method m = method(LibraryController.class, "getFiles", User.class, String.class, int.class, int.class);
        Object[] args = {null, null, 0, PaginationDefaults.MAX_PAGE_SIZE + 1};
        Set<ConstraintViolation<LibraryController>> v =
                executableValidator.validateParameters(mock(LibraryController.class), m, args);
        assertFalse(v.isEmpty());
    }

    @Test
    void libraryController_getFiles_rejectsNegativePage() throws Exception {
        Method m = method(LibraryController.class, "getFiles", User.class, String.class, int.class, int.class);
        Object[] args = {null, null, -1, 50};
        Set<ConstraintViolation<LibraryController>> v =
                executableValidator.validateParameters(mock(LibraryController.class), m, args);
        assertFalse(v.isEmpty());
    }

    @Test
    void libraryController_getFiles_acceptsBoundedSize() throws Exception {
        Method m = method(LibraryController.class, "getFiles", User.class, String.class, int.class, int.class);
        Object[] args = {null, null, 0, PaginationDefaults.MAX_PAGE_SIZE};
        Set<ConstraintViolation<LibraryController>> v =
                executableValidator.validateParameters(mock(LibraryController.class), m, args);
        assertTrue(v.isEmpty(), "expected no violations but got: " + v);
    }

    @Test
    void libraryController_historyTopTracks_rejectsOversizedLimit() throws Exception {
        Method m = method(LibraryController.class, "historyTopTracks", User.class, String.class, Integer.class);
        Object[] args = {null, null, 101};
        Set<ConstraintViolation<LibraryController>> v =
                executableValidator.validateParameters(mock(LibraryController.class), m, args);
        assertFalse(v.isEmpty());
    }

    @Test
    void smartPlaylistController_dryRun_rejectsOversizedSize() throws Exception {
        Method m = method(SmartPlaylistController.class, "dryRun",
                User.class,
                com.stellarideas.grooves.dto.SmartPlaylistPreviewRequest.class,
                int.class, int.class);
        Object[] args = {null, null, 0, PaginationDefaults.MAX_PAGE_SIZE + 1};
        Set<ConstraintViolation<SmartPlaylistController>> v =
                executableValidator.validateParameters(mock(SmartPlaylistController.class), m, args);
        assertFalse(v.isEmpty());
    }

    @Test
    void adminController_getAllUsers_rejectsOversizedSize() throws Exception {
        Method m = method(AdminController.class, "getAllUsers", User.class, int.class, int.class);
        Object[] args = {null, 0, PaginationDefaults.ADMIN_MAX_PAGE_SIZE + 1};
        Set<ConstraintViolation<AdminController>> v =
                executableValidator.validateParameters(mock(AdminController.class), m, args);
        assertFalse(v.isEmpty());
    }

    @Test
    void rediscoveryController_oneHitWonders_rejectsOversizedLimit() throws Exception {
        Method m = method(RediscoveryController.class, "oneHitWonders",
                User.class, Integer.class, Integer.class);
        Object[] args = {null, null, 9999};
        Set<ConstraintViolation<RediscoveryController>> v =
                executableValidator.validateParameters(mock(RediscoveryController.class), m, args);
        assertFalse(v.isEmpty());
    }

    @Test
    void playlistController_getPlaylistTracks_rejectsOversizedSize() throws Exception {
        Method m = method(PlaylistController.class, "getPlaylistTracks",
                User.class, String.class, Integer.class, Integer.class);
        Object[] args = {null, "id", 0, PaginationDefaults.MAX_PAGE_SIZE + 1};
        Set<ConstraintViolation<PlaylistController>> v =
                executableValidator.validateParameters(mock(PlaylistController.class), m, args);
        assertFalse(v.isEmpty());
    }
}
