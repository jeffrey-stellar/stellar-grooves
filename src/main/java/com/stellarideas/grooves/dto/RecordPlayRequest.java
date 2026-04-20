package com.stellarideas.grooves.dto;

import jakarta.validation.constraints.Min;

public class RecordPlayRequest {

    @Min(0)
    private int listenedMs;

    private boolean completed;

    public int getListenedMs() { return listenedMs; }
    public void setListenedMs(int listenedMs) { this.listenedMs = listenedMs; }

    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
}
