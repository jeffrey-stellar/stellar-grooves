package com.stellarideas.grooves.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A user-saved query fragment with a {@code @name} that can be referenced from any
 * smart-playlist query. Resolution happens at execution time — references are
 * looked up against the query owner's phrase library, expanded recursively (with
 * cycle detection), and the resulting AST is handed to the translator.
 *
 * <p>Phrases are private to the owning user. Sharing semantics: when a curator
 * shares a smart playlist that references {@code @jazz-core}, subscribers see
 * the literal source query in the public viewer; their preview resolves the
 * reference against the <em>curator's</em> phrase library (taste vocabulary)
 * but runs criteria against the subscriber's own tracks.
 */
@Document(collection = "smart_playlist_phrases")
@CompoundIndexes({
    @CompoundIndex(name = "user_name", def = "{'userId': 1, 'name': 1}", unique = true)
})
public class SmartPlaylistPhrase {

    /** Public regex for phrase names. Lowercase letters, digits, hyphen, underscore. */
    public static final String NAME_PATTERN = "^[a-z0-9][a-z0-9_-]*$";

    @Id
    private String id;

    @Version
    private Long version;

    @NotBlank
    @Size(min = 1, max = 50)
    @Pattern(regexp = NAME_PATTERN, message = "Phrase name must be lowercase letters/digits/-/_ and start with a letter or digit")
    private String name;

    @NotBlank
    @Size(max = 1000)
    private String body;

    @Size(max = 200)
    private String description;

    @JsonIgnore
    private String userId;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public SmartPlaylistPhrase() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    @JsonIgnore
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SmartPlaylistPhrase that = (SmartPlaylistPhrase) o;
        return java.util.Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(id);
    }
}
