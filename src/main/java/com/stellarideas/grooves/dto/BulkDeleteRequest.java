package com.stellarideas.grooves.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public class BulkDeleteRequest {

    @NotEmpty
    @Size(max = 100, message = "Cannot delete more than 100 files at once")
    private List<@Size(max = 64, message = "Each fileId must not exceed 64 characters") String> fileIds;

    public List<String> getFileIds() { return fileIds; }
    public void setFileIds(List<String> fileIds) { this.fileIds = fileIds; }
}
