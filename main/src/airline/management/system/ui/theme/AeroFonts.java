package airline.management.system.ui.theme;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** AeroDesk Modern SaaS Typography Hierarchy */
public final class AeroFonts {
    private static final String FONT_FAMILY = resolveFamily();

    private AeroFonts() { }

    private static String resolveFamily() {
        try (InputStream input = AeroFonts.class.getResourceAsStream("/airline/management/system/ui/assets/fonts/Manrope-Regular.ttf")) {
            if (input != null) {
                Font font = Font.createFont(Font.TRUETYPE_FONT, input);
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
                return font.getFamily();
            }
        } catch (Exception ignored) {
            // Optional bundled font: a readable system font is used until the resource is available.
        }
        Set<String> fonts = new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        if (fonts.contains("Manrope")) return "Manrope";
        if (fonts.contains("Inter")) return "Inter";
        if (fonts.contains("Segoe UI")) return "Segoe UI";
        if (fonts.contains("Helvetica Neue")) return "Helvetica Neue";
        if (fonts.contains("Arial")) return "Arial";
        return Font.SANS_SERIF;
    }

    private static final String MONO_FAMILY = resolveMonoFamily();

    private static String resolveMonoFamily() {
        Set<String> fonts = new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        if (fonts.contains("JetBrains Mono")) return "JetBrains Mono";
        if (fonts.contains("Cascadia Code")) return "Cascadia Code";
        if (fonts.contains("Consolas")) return "Consolas";
        if (fonts.contains("Menlo")) return "Menlo";
        if (fonts.contains("Monaco")) return "Monaco";
        if (fonts.contains("Courier New")) return "Courier New";
        return Font.MONOSPACED;
    }

    public static final Font TITLE_PAGE = new Font(FONT_FAMILY, Font.BOLD, 26);     // 24–28px, 700
    public static final Font PAGE_SUBTITLE = new Font(FONT_FAMILY, Font.PLAIN, 14); // 13–14px, 400-500
    public static final Font TITLE_SECTION = new Font(FONT_FAMILY, Font.BOLD, 17);  // 16–18px, 600-700
    public static final Font TITLE_CARD = new Font(FONT_FAMILY, Font.BOLD, 15);
    public static final Font KPI_VALUE = new Font(FONT_FAMILY, Font.BOLD, 32);      // 28–32px, 700
    public static final Font METRIC_LABEL = new Font(FONT_FAMILY, Font.PLAIN, 13);  // 12–13px, 500
    public static final Font BODY = new Font(FONT_FAMILY, Font.PLAIN, 13);          // 13–14px
    public static final Font BODY_BOLD = new Font(FONT_FAMILY, Font.BOLD, 13);
    public static final Font BUTTON = new Font(FONT_FAMILY, Font.BOLD, 13);
    public static final Font TABLE = new Font(FONT_FAMILY, Font.PLAIN, 13);         // 13px
    public static final Font TABLE_HEADER = new Font(FONT_FAMILY, Font.BOLD, 12);
    public static final Font CAPTION = new Font(FONT_FAMILY, Font.PLAIN, 12);       // 12px
    public static final Font CAPTION_BOLD = new Font(FONT_FAMILY, Font.BOLD, 12);
    public static final Font MUTED = new Font(FONT_FAMILY, Font.PLAIN, 11);
    public static final Font BADGE = new Font(FONT_FAMILY, Font.BOLD, 11);
    public static final Font SECTION_CATEGORY = new Font(FONT_FAMILY, Font.BOLD, 11);

    // Restrained Monospace Typography for Operational Identifiers (PNR, Flight No, Gate, Baggage Tag, Reference IDs)
    public static final Font MONO = new Font(MONO_FAMILY, Font.PLAIN, 12);
    public static final Font MONO_BOLD = new Font(MONO_FAMILY, Font.BOLD, 12);
    public static final Font MONO_SM = new Font(MONO_FAMILY, Font.PLAIN, 11);
    public static final Font MONO_SM_BOLD = new Font(MONO_FAMILY, Font.BOLD, 11);
    public static final Font MONO_LG_BOLD = new Font(MONO_FAMILY, Font.BOLD, 14);
}
