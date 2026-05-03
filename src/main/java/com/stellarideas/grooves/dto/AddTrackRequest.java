package com.stellarideas.grooves.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AddTrackRequest {

    @NotBlank(message = "fileId is required")
    @Size(max = 64, message = "fileId must not exceed 64 characters")
    private String fileId;

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
}
