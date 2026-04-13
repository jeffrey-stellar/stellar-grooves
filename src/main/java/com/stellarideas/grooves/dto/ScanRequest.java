package com.stellarideas.grooves.dto;

import jakarta.validation.constraints.NotBlank;

public class ScanRequest {

    @NotBlank(message = "Directory path must not be empty")
    private String path;

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
}
