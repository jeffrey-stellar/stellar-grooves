package com.stellarideas.grooves.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ScanRequest {

    @NotBlank(message = "Directory path must not be empty")
    @Size(max = 1024, message = "Directory path must not exceed 1024 characters")
    @Pattern(regexp = "^[^\\x00]+$", message = "Directory path must not contain null bytes")
    private String path;

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
}
