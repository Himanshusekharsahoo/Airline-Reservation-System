package airline.management.system.ui.theme;

import java.awt.Color;

/** AeroDesk Modern Light SaaS Enterprise Palette */
public final class AeroColors {
    private AeroColors() { }

    // Canvas & Surfaces
    public static final Color BACKGROUND = new Color(0xF8, 0xFA, 0xFC);     // Canvas #F8FAFC
    public static final Color CARD_BG = Color.WHITE;                        // Surface #FFFFFF
    public static final Color CARD_SUBTLE = new Color(0xF8, 0xFA, 0xFC);    // Subtle Surface #F8FAFC

    // Light Sidebar System
    public static final Color SIDEBAR = Color.WHITE;                         // Light Sidebar #FFFFFF
    public static final Color SIDEBAR_HOVER = new Color(0xF1, 0xF5, 0xF9);   // Subtle Hover #F1F5F9
    public static final Color SIDEBAR_ACTIVE = new Color(0xEF, 0xF6, 0xFF);  // Blue-tinted Active #EFF6FF
    public static final Color SIDEBAR_ACTIVE_BAR = new Color(0x25, 0x63, 0xEB); // Active Indicator #2563EB
    public static final Color SIDEBAR_TEXT = new Color(0x0F, 0x17, 0x2A);   // Dark text #0F172A
    public static final Color SIDEBAR_MUTED = new Color(0x64, 0x74, 0x8B);  // Muted #64748B
    public static final Color SIDEBAR_SECTION = new Color(0x94, 0xA3, 0xB8);// Section Header #94A3B8
    public static final Color SIDEBAR_BORDER = new Color(0xE2, 0xE8, 0xF0); // Right border #E2E8F0

    // Brand System (selective use)
    public static final Color PRIMARY = new Color(0x1E, 0x5E, 0xFF);        // Brand Blue #1E5EFF
    public static final Color PRIMARY_HOVER = new Color(0x17, 0x46, 0xB8);  // Brand Dark #1746B8
    public static final Color PRIMARY_PRESSED = new Color(0x1E, 0x40, 0xAF);
    public static final Color ACCENT = new Color(0x3B, 0x82, 0xF6);

    // Neutral Text Hierarchy
    public static final Color TEXT_MAIN = new Color(0x11, 0x18, 0x27);       // #111827
    public static final Color TEXT_SECONDARY = new Color(0x47, 0x55, 0x69);  // #475569
    public static final Color TEXT_MUTED = new Color(0x4B, 0x55, 0x63);      // #4B5563 (Improved contrast)

    // Borders & Dividers
    public static final Color BORDER = new Color(0xE2, 0xE8, 0xF0);           // #E2E8F0
    public static final Color BORDER_SUBTLE = new Color(0xF1, 0xF5, 0xF9);    // #F1F5F9
    public static final Color BORDER_FOCUS = new Color(0x25, 0x63, 0xEB);

    // Table System
    public static final Color TABLE_HEADER_BG = new Color(0xF8, 0xFA, 0xFC);  // #F8FAFC
    public static final Color TABLE_ROW_ALT = new Color(0xFA, 0xFB, 0xFD);
    public static final Color TABLE_SELECTION = new Color(0xEF, 0xF6, 0xFF);  // #EFF6FF
    public static final Color TABLE_HOVER = new Color(0xF1, 0xF5, 0xF9);

    // Semantic States (soft backgrounds + crisp text)
    public static final Color SUCCESS = new Color(0x15, 0x80, 0x3D);          // #15803D
    public static final Color SUCCESS_BG = new Color(0xF0, 0xFD, 0xF4);       // #F0FDF4
    public static final Color WARNING = new Color(0xB4, 0x53, 0x09);          // #B45309
    public static final Color WARNING_BG = new Color(0xFF, 0xFB, 0xEB);       // #FFFBEB
    public static final Color DANGER = new Color(0xB9, 0x1C, 0x1C);           // #B91C1C
    public static final Color DANGER_BG = new Color(0xFE, 0xF2, 0xF2);        // #FEF2F2
    public static final Color INFO = new Color(0x25, 0x63, 0xEB);             // #2563EB
    public static final Color INFO_BG = new Color(0xEF, 0xF6, 0xFF);          // #EFF6FF
    public static final Color NEUTRAL_BG = new Color(0xF1, 0xF5, 0xF9);       // #F1F5F9
}
