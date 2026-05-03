package com.stellarideas.grooves.dto;

import com.stellarideas.grooves.model.SmartPlaylistPhrase;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class SmartPlaylistPhraseRequest {

    @NotBlank
    @Size(min = 1, max = 50)
    @Pattern(regexp = SmartPlaylistPhrase.NAME_PATTERN,
            message = "Phrase name must be lowercase letters/digits/-/_ and start with a letter or digit")
    private String name;

    @NotBlank
    @Size(max = 1000)
    private String body;

    @Size(max = 200)
    private String description;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
