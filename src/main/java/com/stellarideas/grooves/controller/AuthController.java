package com.stellarideas.grooves.controller;

import com.stellarideas.grooves.model.Role;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.security.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @Autowired
    AuthenticationManager authenticationManager;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JwtUtils jwtUtils;

    @PostMapping("/signin")
    public ResponseEntity<?> authenticateUser(@RequestBody Map<String, String> loginRequest) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginRequest.get("username"), loginRequest.get("password")));

        SecurityContextHolder.getContext().setAuthentication(authentication);
        String jwt = jwtUtils.generateJwtToken(authentication);
        
        return ResponseEntity.ok(Map.of("token", jwt, "username", loginRequest.get("username")));
    }

    @PostMapping("/signup")
    public ResponseEntity<?> registerUser(@RequestBody Map<String, Object> signUpRequest) {
        if (userRepository.existsByUsername((String) signUpRequest.get("username"))) {
            return ResponseEntity.badRequest().body("Error: Username is already taken!");
        }

        User user = User.builder()
                .username((String) signUpRequest.get("username"))
                .email((String) signUpRequest.get("email"))
                .password(encoder.encode((String) signUpRequest.get("password")))
                .build();

        Set<Role> roles = new HashSet<>();
        roles.add(Role.ROLE_USER);
        user.setRoles(roles);
        userRepository.save(user);

        return ResponseEntity.ok("User registered successfully!");
    }
}
