package com.stellarideas.grooves.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for {@code POST /api/v1/library/files/tags/bulk}. Applies {@code add}
 * and/or {@code remove} tags to every owned, non-deleted file in {@code fileIds}.
 * Either {@code add} or {@code remove} must be non-empty (enforced at the service layer).
 */
public class BulkTagsRequest {

    @NotEmpty(message = "fileIds must not be empty")
    @Size(max = 1000, message = "At most 1000 files per bulk tag operation")
    private List<@Size(max = 64, message = "Each fileId must not exceed 64 characters") String> fileIds;

    @Size(max = 20, message = "At most 20 tags may be added at once")
    private List<@Size(max = 50, message = "Each tag must be 50 characters or fewer") String> add;

    @Size(max = 20, message = "At most 20 tags may be removed at once")
    private List<@Size(max = 50, message = "Each tag must be 50 characters or fewer") String> remove;

    public List<String> getFileIds() { return fileIds; }
    public void setFileIds(List<String> fileIds) { this.fileIds = fileIds; }

    public List<String> getAdd() { return add; }
    public void setAdd(List<String> add) { this.add = add; }

    public List<String> getRemove() { return remove; }
    public void setRemove(List<String> remove) { this.remove = remove; }
}
