package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.service.MusicScannerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/library")
public class LibraryController {

    @Autowired
    private MusicScannerService scannerService;

    @Autowired
    private MusicFileRepository musicFileRepository;

    @Autowired
    private UserRepository userRepository;

    @PostMapping("/scan")
    public ResponseEntity<?> scanDirectory(@RequestBody Map<String, String> request) {
        String path = request.get("path");
        User user = getCurrentUser();
        try {
            scannerService.scanDirectory(user, path);
            return ResponseEntity.ok("Scan completed successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Scan failed: " + e.getMessage());
        }
    }

    @GetMapping("/files")
    public List<MusicFile> getFiles() {
        return musicFileRepository.findByUser(getCurrentUser());
    }

    private User getCurrentUser() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String username = ((UserDetails) principal).getUsername();
        return userRepository.findByUsername(username).orElseThrow();
    }
}
