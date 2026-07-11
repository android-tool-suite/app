package com.example.shizukuaccessibilitygrant.plugin.api;

import java.util.Objects;

public final class HomeWidgetSize {
    public final int widthUnits;
    public final int heightUnits;

    public HomeWidgetSize(int widthUnits, int heightUnits) {
        if (widthUnits < 1 || widthUnits > 4 || heightUnits < 1 || heightUnits > 4) {
            throw new IllegalArgumentException("Widget size must be between 1 and 4 grid units");
        }
        this.widthUnits = widthUnits;
        this.heightUnits = heightUnits;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HomeWidgetSize)) return false;
        HomeWidgetSize size = (HomeWidgetSize) other;
        return widthUnits == size.widthUnits && heightUnits == size.heightUnits;
    }

    @Override
    public int hashCode() {
        return Objects.hash(widthUnits, heightUnits);
    }
}
