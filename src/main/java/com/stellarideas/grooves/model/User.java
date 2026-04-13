package com.stellarideas.grooves.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Document(collection = "users")
public class User {
    @Id
    private String id;

    @NotBlank
    @Size(min = 3, max = 20)
    @Indexed(unique = true)
    private String username;

    @JsonIgnore
    private String password;

    @NotBlank
    @Email
    @Indexed(unique = true)
    private String email;

    private String musicDirectory;

    private Set<Role> roles = new HashSet<>();

    private boolean accountLocked = false;
    private boolean enabled = true;

    private int failedLoginAttempts = 0;
    private Instant lockoutExpiry;

    private String scanSchedule;
    private String scanPath;
    private Instant lastScheduledScan;

    public User() {
    }

    public User(String id, String username, String password, String email, String musicDirectory,
                Set<Role> roles, boolean accountLocked, boolean enabled,
                int failedLoginAttempts, Instant lockoutExpiry,
                String scanSchedule, String scanPath, Instant lastScheduledScan) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.email = email;
        this.musicDirectory = musicDirectory;
        this.roles = roles;
        this.accountLocked = accountLocked;
        this.enabled = enabled;
        this.failedLoginAttempts = failedLoginAttempts;
        this.lockoutExpiry = lockoutExpiry;
        this.scanSchedule = scanSchedule;
        this.scanPath = scanPath;
        this.lastScheduledScan = lastScheduledScan;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    @JsonIgnore
    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getMusicDirectory() {
        return musicDirectory;
    }

    public void setMusicDirectory(String musicDirectory) {
        this.musicDirectory = musicDirectory;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles;
    }

    public boolean isAccountLocked() {
        return accountLocked;
    }

    public void setAccountLocked(boolean accountLocked) {
        this.accountLocked = accountLocked;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public void setFailedLoginAttempts(int failedLoginAttempts) {
        this.failedLoginAttempts = failedLoginAttempts;
    }

    public Instant getLockoutExpiry() {
        return lockoutExpiry;
    }

    public void setLockoutExpiry(Instant lockoutExpiry) {
        this.lockoutExpiry = lockoutExpiry;
    }

    public String getScanSchedule() {
        return scanSchedule;
    }

    public void setScanSchedule(String scanSchedule) {
        this.scanSchedule = scanSchedule;
    }

    public String getScanPath() {
        return scanPath;
    }

    public void setScanPath(String scanPath) {
        this.scanPath = scanPath;
    }

    public Instant getLastScheduledScan() {
        return lastScheduledScan;
    }

    public void setLastScheduledScan(Instant lastScheduledScan) {
        this.lastScheduledScan = lastScheduledScan;
    }

    public static UserBuilder builder() {
        return new UserBuilder();
    }

    public static class UserBuilder {
        private String id;
        private String username;
        private String password;
        private String email;
        private String musicDirectory;
        private Set<Role> roles;
        private boolean accountLocked = false;
        private boolean enabled = true;
        private int failedLoginAttempts = 0;
        private Instant lockoutExpiry;
        private String scanSchedule;
        private String scanPath;
        private Instant lastScheduledScan;

        UserBuilder() {
        }

        public UserBuilder id(String id) {
            this.id = id;
            return this;
        }

        public UserBuilder username(String username) {
            this.username = username;
            return this;
        }

        public UserBuilder password(String password) {
            this.password = password;
            return this;
        }

        public UserBuilder email(String email) {
            this.email = email;
            return this;
        }

        public UserBuilder musicDirectory(String musicDirectory) {
            this.musicDirectory = musicDirectory;
            return this;
        }

        public UserBuilder roles(Set<Role> roles) {
            this.roles = roles;
            return this;
        }

        public UserBuilder accountLocked(boolean accountLocked) {
            this.accountLocked = accountLocked;
            return this;
        }

        public UserBuilder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public UserBuilder failedLoginAttempts(int failedLoginAttempts) {
            this.failedLoginAttempts = failedLoginAttempts;
            return this;
        }

        public UserBuilder lockoutExpiry(Instant lockoutExpiry) {
            this.lockoutExpiry = lockoutExpiry;
            return this;
        }

        public UserBuilder scanSchedule(String scanSchedule) {
            this.scanSchedule = scanSchedule;
            return this;
        }

        public UserBuilder scanPath(String scanPath) {
            this.scanPath = scanPath;
            return this;
        }

        public UserBuilder lastScheduledScan(Instant lastScheduledScan) {
            this.lastScheduledScan = lastScheduledScan;
            return this;
        }

        public User build() {
            return new User(id, username, password, email, musicDirectory, roles, accountLocked, enabled,
                    failedLoginAttempts, lockoutExpiry, scanSchedule, scanPath, lastScheduledScan);
        }
    }
}
