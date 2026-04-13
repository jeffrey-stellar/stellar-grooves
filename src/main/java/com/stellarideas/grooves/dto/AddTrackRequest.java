package com.stellarideas.grooves.dto;

import jakarta.validation.constraints.NotBlank;

public class AddTrackRequest {

    @NotBlank(message = "fileId is required")
    private String fileId;

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
}
