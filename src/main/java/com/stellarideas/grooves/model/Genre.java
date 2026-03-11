package com.stellarideas.grooves.model;

public enum Genre {
    CLASSIC_ROCK("Classic Rock"),
    HARD_ROCK("Hard Rock"),
    HAIR_METAL("Hair Metal"),
    HEAVY_METAL("Heavy Metal"),
    THRASH_METAL("Thrash Metal"),
    OTHER("Other");

    private final String displayName;

    Genre(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
