package com.stellarideas.grooves.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Persistent record of a single scan invocation. Created when a scan is queued; updated
 * periodically by the scan worker; terminal on {@link Status#COMPLETED}, {@link Status#FAILED},
 * or {@link Status#TIMED_OUT}.
 *
 * <p>Serves two purposes: (1) lets SSE reconnects read current progress when a client
 * disconnects mid-scan, (2) gives HTTP callers a handle to poll via
 * {@code GET /scan/status} without waiting on the (potentially long) scan to finish.
 */
@Document(collection = "scan_jobs")
@CompoundIndexes({
        @CompoundIndex(name = "user_status_startedAt",
                def = "{'userId': 1, 'status': 1, 'startedAt': -1}"),
        @CompoundIndex(name = "user_startedAt",
                def = "{'userId': 1, 'startedAt': -1}")
})
public class ScanJob {

    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED, TIMED_OUT }

    public enum Type { MANUAL, SCHEDULED }

    @Id
    private String id;

    @Indexed
    private String userId;

    private String path;
    private Status status;
    private Type type;

    private int filesSaved;
    private int filesSkipped;
    private int filesErrored;
    private String currentFile;
    private String errorMessage;

    private Instant queuedAt;
    private Instant startedAt;
    private Instant updatedAt;
    private Instant finishedAt;

    public ScanJob() {}

    public boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.FAILED || status == Status.TIMED_OUT;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public int getFilesSaved() { return filesSaved; }
    public void setFilesSaved(int v) { this.filesSaved = v; }

    public int getFilesSkipped() { return filesSkipped; }
    public void setFilesSkipped(int v) { this.filesSkipped = v; }

    public int getFilesErrored() { return filesErrored; }
    public void setFilesErrored(int v) { this.filesErrored = v; }

    public String getCurrentFile() { return currentFile; }
    public void setCurrentFile(String v) { this.currentFile = v; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }

    public Instant getQueuedAt() { return queuedAt; }
    public void setQueuedAt(Instant v) { this.queuedAt = v; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { this.startedAt = v; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant v) { this.finishedAt = v; }
}
