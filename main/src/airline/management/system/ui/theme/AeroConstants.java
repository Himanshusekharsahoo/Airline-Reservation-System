package airline.management.system.ui.theme;

import java.awt.Dimension;

/** AeroDesk UI Dimensions and Constants */
public final class AeroConstants {
    private AeroConstants() { }

    public static final String APP_TITLE = "AeroDesk — Airline Operations Control Center";
    public static final String BRAND_NAME = "AERODESK";
    public static final String BRAND_TAGLINE = "Operations Platform";

    public static final int WINDOW_MIN_WIDTH = 1200;
    public static final int WINDOW_MIN_HEIGHT = 750;
    public static final Dimension MIN_WINDOW_SIZE = new Dimension(WINDOW_MIN_WIDTH, WINDOW_MIN_HEIGHT);

    public static final int SIDEBAR_WIDTH = 248;
    public static final int TOPBAR_HEIGHT = 64;

    public static final int CORNER_RADIUS = 12;
    public static final int BUTTON_RADIUS = 8;
    public static final int BUTTON_HEIGHT = 36;
    public static final int INPUT_HEIGHT = 34;         // Consistent input height
    public static final int TABLE_ROW_HEIGHT = 44;     // 44-48px comfortable table rows
}
