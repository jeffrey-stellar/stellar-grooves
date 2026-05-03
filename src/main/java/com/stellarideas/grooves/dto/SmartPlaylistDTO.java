package com.stellarideas.grooves.dto;

import com.stellarideas.grooves.model.SmartPlaylist;

import java.time.Instant;

public class SmartPlaylistDTO {

    private String id;
    private String name;
    private String queryString;
    private String description;
    private String shareToken;
    private boolean subscribed;
    private String curatorUsername;
    private Boolean sourceAvailable;
    private Long subscriberCount;
    private Instant createdAt;
    private Instant updatedAt;

    public static class View {
        public String resolvedQuery;
        public String resolvedDescription;
        public String curatorUsername;
        public Boolean sourceAvailable;
        public Long subscriberCount;
    }

    /**
     * Build a DTO. The {@link View} bundle carries the resolved/computed values
     * (live query/description for subscriptions, curator username, source-available
     * flag, subscriber count). Pass {@code null} fields to leave them off the DTO.
     */
    public static SmartPlaylistDTO from(SmartPlaylist sp, View view) {
        SmartPlaylistDTO dto = new SmartPlaylistDTO();
        dto.id = sp.getId();
        dto.name = sp.getName();
        dto.queryString = view != null && view.resolvedQuery != null ? view.resolvedQuery : sp.getQueryString();
        dto.description = view != null && view.resolvedDescription != null ? view.resolvedDescription : sp.getDescription();
        dto.shareToken = sp.getShareToken();
        dto.subscribed = sp.isSubscription();
        dto.curatorUsername = view != null ? view.curatorUsername : null;
        dto.sourceAvailable = view != null ? view.sourceAvailable : null;
        dto.subscriberCount = view != null ? view.subscriberCount : null;
        dto.createdAt = sp.getCreatedAt();
        dto.updatedAt = sp.getUpdatedAt();
        return dto;
    }

    /** Convenience for owner rows with no resolution required. */
    public static SmartPlaylistDTO from(SmartPlaylist sp) {
        return from(sp, null);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getQueryString() { return queryString; }
    public void setQueryString(String queryString) { this.queryString = queryString; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getShareToken() { return shareToken; }
    public void setShareToken(String shareToken) { this.shareToken = shareToken; }

    public boolean isSubscribed() { return subscribed; }
    public void setSubscribed(boolean subscribed) { this.subscribed = subscribed; }

    public String getCuratorUsername() { return curatorUsername; }
    public void setCuratorUsername(String curatorUsername) { this.curatorUsername = curatorUsername; }

    public Boolean getSourceAvailable() { return sourceAvailable; }
    public void setSourceAvailable(Boolean sourceAvailable) { this.sourceAvailable = sourceAvailable; }

    public Long getSubscriberCount() { return subscriberCount; }
    public void setSubscriberCount(Long subscriberCount) { this.subscriberCount = subscriberCount; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
