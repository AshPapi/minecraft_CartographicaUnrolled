package com.cartographica_unrolled.data;

import java.util.Objects;
import java.util.UUID;

public class MapMarker {
    public enum BannerType {
        WHITE(0xFFF9FFFE),
        LIGHT_GRAY(0xFF9D9D97),
        GRAY(0xFF474F52),
        BLACK(0xFF1D1D21),
        BROWN(0xFF835432),
        RED(0xFFB02E26),
        ORANGE(0xFFF9801D),
        YELLOW(0xFFFED83D),
        LIME(0xFF80C71F),
        GREEN(0xFF5E7C16),
        CYAN(0xFF169C9C),
        LIGHT_BLUE(0xFF3AB3DA),
        BLUE(0xFF3C44AA),
        PURPLE(0xFF8932B8),
        MAGENTA(0xFFC74EBD),
        PINK(0xFFF38BAA);

        public final int color;

        BannerType(int color) {
            this.color = color;
        }
    }

    private final UUID id;
    private final float worldX;
    private final float worldZ;
    private final BannerType type;
    private String label;
    private final UUID authorUuid;
    private final String authorName;

    public MapMarker(UUID id, float worldX, float worldZ, BannerType type, String label) {
        this(id, worldX, worldZ, type, label, null, null);
    }

    public MapMarker(UUID id, float worldX, float worldZ, BannerType type, String label, UUID authorUuid, String authorName) {
        this.id = id;
        this.worldX = worldX;
        this.worldZ = worldZ;
        this.type = type;
        this.label = label;
        this.authorUuid = authorUuid;
        this.authorName = authorName;
    }

    public UUID getId() {
        return id;
    }

    public float getWorldX() {
        return worldX;
    }

    public float getWorldZ() {
        return worldZ;
    }

    public BannerType getType() {
        return type;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public UUID getAuthorUuid() {
        return authorUuid;
    }

    public String getAuthorName() {
        return authorName;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MapMarker other)) {
            return false;
        }
        return Float.compare(this.worldX, other.worldX) == 0
                && Float.compare(this.worldZ, other.worldZ) == 0
                && Objects.equals(this.id, other.id)
                && this.type == other.type
                && Objects.equals(this.label, other.label)
                && Objects.equals(this.authorUuid, other.authorUuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.id, this.worldX, this.worldZ, this.type, this.label, this.authorUuid);
    }
}
