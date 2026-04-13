package com.stellarideas.grooves.dto;

public class ScanScheduleRequest {

    private String cronExpression;
    private String path;

    public ScanScheduleRequest() {}

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
