package airline.management.system.ui.components;

import airline.management.system.ui.theme.AeroColors;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.JComponent;

/** Small, dependency-free vector icons used by the AeroDesk shell. */
public final class AeroIcon extends JComponent {
    public enum Type {
        DASHBOARD, FLIGHT, ALERT, BOARDING, RESERVATION, PASSENGER, CHECKIN,
        TRIPS, BAGGAGE, CLAIM, PAYMENT, CANCEL, REPORTS, STAFF, SETTINGS,
        LOGOUT, CHEVRON_LEFT, CHEVRON_RIGHT, CALENDAR, BELL, HELP, REFRESH,
        SEARCH, FILTER, PLUS, EDIT, TRASH, VIEW, CLOSE, EXPORT, MORE,
        STATION, RADIO, ARROW_RIGHT, GATE, CHECK, CHEVRON_DOWN
    }

    private final Type type;
    private Color iconColor = AeroColors.TEXT_SECONDARY;

    public AeroIcon(Type type, int size) {
        this.type = type;
        setPreferredSize(new Dimension(size, size));
        setMinimumSize(getPreferredSize());
        setOpaque(false);
    }

    public AeroIcon color(Color color) {
        iconColor = color == null ? AeroColors.TEXT_SECONDARY : color;
        repaint();
        return this;
    }

    public static Type fromLegacySymbol(String symbol) {
        return switch (symbol == null ? "" : symbol.toLowerCase().trim()) {
            case "dashboard", "overview" -> Type.DASHBOARD;
            case "flight", "flights", "aircraft" -> Type.FLIGHT;
            case "passenger", "passengers", "users" -> Type.PASSENGER;
            case "checkin", "check-in" -> Type.CHECKIN;
            case "alert", "warning", "disruption", "irops" -> Type.ALERT;
            case "reservation", "booking" -> Type.RESERVATION;
            case "baggage", "luggage" -> Type.BAGGAGE;
            case "claim", "pir", "claims" -> Type.CLAIM;
            case "payment", "finance", "settlement" -> Type.PAYMENT;
            case "cancel", "cancellation" -> Type.CANCEL;
            case "search" -> Type.SEARCH;
            case "filter" -> Type.FILTER;
            case "add", "plus", "new" -> Type.PLUS;
            case "edit" -> Type.EDIT;
            case "trash", "delete", "remove" -> Type.TRASH;
            case "view", "eye" -> Type.VIEW;
            case "close", "dismiss" -> Type.CLOSE;
            case "export" -> Type.EXPORT;
            case "station", "hub", "airport" -> Type.STATION;
            case "radio", "live", "signal" -> Type.RADIO;
            case "gate" -> Type.GATE;
            case "check", "confirmed", "valid" -> Type.CHECK;
            case "arrow_right", "next" -> Type.ARROW_RIGHT;
            case "down", "chevron_down", "arrow_down" -> Type.CHEVRON_DOWN;
            case "⊞" -> Type.DASHBOARD;
            case "✈" -> Type.FLIGHT;
            case "⚡", "⚠", "⚠️", "!" -> Type.ALERT;
            case "⚑", "🚪" -> Type.BOARDING;
            case "🎫" -> Type.RESERVATION;
            case "👥" -> Type.PASSENGER;
            case "✓", "✔" -> Type.CHECKIN;
            case "🗺" -> Type.TRIPS;
            case "🧳" -> Type.BAGGAGE;
            case "📋", "⚖", "🔍" -> Type.CLAIM;
            case "💳", "₹" -> Type.PAYMENT;
            case "✕", "✖", "↩" -> Type.CANCEL;
            case "📊" -> Type.REPORTS;
            case "👤" -> Type.STAFF;
            case "⚙" -> Type.SETTINGS;
            case "⏻" -> Type.LOGOUT;
            case "↻", "🔄" -> Type.REFRESH;
            default -> Type.DASHBOARD;
        };
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(iconColor);
        float s = Math.min(getWidth(), getHeight());
        float u = s / 24f;
        g.scale(u, u);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.translate((24 - getWidth() / u) / 2f, (24 - getHeight() / u) / 2f);
        switch (type) {
            case DASHBOARD -> { g.drawRoundRect(3, 3, 6, 6, 2, 2); g.drawRoundRect(15, 3, 6, 6, 2, 2); g.drawRoundRect(3, 15, 6, 6, 2, 2); g.drawRoundRect(15, 15, 6, 6, 2, 2); }
            case FLIGHT -> aircraft(g);
            case ALERT -> { Path2D alert = new Path2D.Float(); alert.moveTo(12, 3); alert.lineTo(21, 19); alert.lineTo(3, 19); alert.closePath(); g.draw(alert); g.drawLine(12, 9, 12, 14); g.fillOval(11, 16, 2, 2); }
            case BOARDING -> { g.drawRoundRect(4, 3, 12, 18, 2, 2); g.drawLine(10, 12, 21, 12); g.drawLine(17, 9, 21, 12); g.drawLine(17, 15, 21, 12); }
            case RESERVATION -> { g.drawRoundRect(3, 6, 18, 12, 2, 2); g.drawLine(7, 6, 7, 18); g.drawLine(17, 6, 17, 18); }
            case PASSENGER, STAFF -> { g.drawOval(8, 3, 8, 8); g.drawRoundRect(4, 14, 16, 7, 4, 4); }
            case CHECKIN -> { g.drawRoundRect(3, 4, 18, 16, 3, 3); Path2D chk = new Path2D.Float(); chk.moveTo(7.5f, 12f); chk.lineTo(10.5f, 15f); chk.lineTo(16.5f, 9f); g.draw(chk); }
            case TRIPS -> { g.drawOval(3, 4, 6, 6); g.drawOval(15, 14, 6, 6); Path2D p = new Path2D.Float(); p.moveTo(9, 7); p.curveTo(14, 7, 10, 17, 15, 17); g.draw(p); }
            case BAGGAGE -> { g.drawRoundRect(3, 7, 18, 13, 2, 2); g.drawRoundRect(8, 3, 8, 5, 2, 2); g.drawLine(3, 12, 21, 12); }
            case CLAIM -> { g.drawRoundRect(5, 3, 14, 18, 2, 2); g.drawLine(9, 8, 15, 8); g.drawLine(9, 12, 15, 12); g.drawLine(9, 16, 13, 16); }
            case PAYMENT -> { g.drawRoundRect(3, 5, 18, 14, 2, 2); g.drawLine(3, 10, 21, 10); g.drawLine(7, 15, 12, 15); }
            case CANCEL -> { drawCircle(g, 12, 12, 9); g.drawLine(8, 8, 16, 16); g.drawLine(16, 8, 8, 16); }
            case REPORTS -> { g.drawLine(3, 19, 21, 19); g.drawLine(7, 18, 7, 12); g.drawLine(12, 18, 12, 5); g.drawLine(17, 18, 17, 9); }
            case SETTINGS -> { drawCircle(g, 12, 12, 4); for (int i = 0; i < 8; i++) { double a = Math.PI * i / 4.0; double cos = Math.cos(a); double sin = Math.sin(a); Path2D spoke = new Path2D.Double(); spoke.moveTo(12.0 + cos * 5.0, 12.0 + sin * 5.0); spoke.lineTo(12.0 + cos * 8.5, 12.0 + sin * 8.5); g.draw(spoke); } }
            case LOGOUT -> { g.drawRoundRect(3, 3, 11, 18, 2, 2); g.drawLine(10, 12, 21, 12); g.drawLine(18, 9, 21, 12); g.drawLine(18, 15, 21, 12); }
            case CHEVRON_LEFT -> { g.drawLine(15, 5, 8, 12); g.drawLine(8, 12, 15, 19); }
            case CHEVRON_RIGHT -> { g.drawLine(9, 5, 16, 12); g.drawLine(16, 12, 9, 19); }
            case CALENDAR -> { g.drawRoundRect(3, 5, 18, 16, 2, 2); g.drawLine(3, 10, 21, 10); g.drawLine(8, 3, 8, 7); g.drawLine(16, 3, 16, 7); }
            case BELL -> { g.drawRoundRect(7, 4, 10, 14, 5, 5); g.drawLine(5, 18, 19, 18); g.drawLine(10, 21, 14, 21); }
            case HELP -> { drawCircle(g, 12, 12, 9); g.drawArc(9, 6, 6, 6, 0, 180); g.drawLine(15, 9, 12, 12); g.drawLine(12, 12, 12, 14); g.fillOval(11, 16, 2, 2); }
            case REFRESH -> { g.drawArc(4, 4, 16, 16, 40, 280); g.drawLine(19, 5, 19, 10); g.drawLine(19, 5, 14, 5); }
            case SEARCH -> { drawCircle(g, 10, 10, 6); g.drawLine(14, 14, 20, 20); }
            case FILTER -> { Path2D p = new Path2D.Float(); p.moveTo(4, 5); p.lineTo(20, 5); p.lineTo(14, 13); p.lineTo(14, 19); p.lineTo(10, 17); p.lineTo(10, 13); p.closePath(); g.draw(p); }
            case PLUS -> { g.drawLine(12, 5, 12, 19); g.drawLine(5, 12, 19, 12); }
            case EDIT -> { Path2D p = new Path2D.Float(); p.moveTo(4, 20); p.lineTo(8, 20); p.lineTo(19, 9); p.lineTo(15, 5); p.lineTo(4, 16); p.closePath(); g.draw(p); }
            case TRASH -> { g.drawLine(4, 6, 20, 6); g.drawRoundRect(6, 6, 12, 14, 2, 2); g.drawLine(9, 3, 15, 3); g.drawLine(10, 10, 10, 16); g.drawLine(14, 10, 14, 16); }
            case VIEW -> { Path2D p = new Path2D.Float(); p.moveTo(3, 12); p.curveTo(8, 6, 16, 6, 21, 12); p.curveTo(16, 18, 8, 18, 3, 12); g.draw(p); drawCircle(g, 12, 12, 3); }
            case CLOSE -> { g.drawLine(6, 6, 18, 18); g.drawLine(18, 6, 6, 18); }
            case EXPORT -> { g.drawRoundRect(4, 12, 16, 9, 2, 2); g.drawLine(12, 4, 12, 15); g.drawLine(8, 8, 12, 4); g.drawLine(16, 8, 12, 4); }
            case MORE -> { g.fillOval(5, 11, 3, 3); g.fillOval(11, 11, 3, 3); g.fillOval(17, 11, 3, 3); }
            case STATION -> { g.drawRoundRect(5, 4, 14, 16, 2, 2); g.drawLine(5, 9, 19, 9); g.drawLine(9, 20, 9, 15); g.drawLine(15, 20, 15, 15); g.drawLine(9, 15, 15, 15); }
            case RADIO -> { g.fillOval(11, 11, 3, 3); g.drawArc(7, 7, 10, 10, -45, 90); g.drawArc(4, 4, 16, 16, -45, 90); g.drawArc(7, 7, 10, 10, 135, 90); g.drawArc(4, 4, 16, 16, 135, 90); }
            case ARROW_RIGHT -> { g.drawLine(5, 12, 19, 12); g.drawLine(13, 6, 19, 12); g.drawLine(13, 18, 19, 12); }
            case GATE -> { g.drawRoundRect(5, 3, 14, 18, 2, 2); g.drawLine(5, 11, 12, 11); g.drawArc(11, 9, 6, 6, 0, 180); g.fillOval(15, 14, 2, 2); }
            case CHECK -> { g.drawLine(5, 12, 10, 17); g.drawLine(10, 17, 19, 7); }
            case CHEVRON_DOWN -> { g.drawLine(6, 10, 12, 16); g.drawLine(12, 16, 18, 10); }
        }
        g.dispose();
    }

    private static void aircraft(Graphics2D g) {
        Path2D p = new Path2D.Float();
        p.moveTo(2.5f, 15.5f);
        p.lineTo(9.5f, 12.5f);
        p.lineTo(14.5f, 4.5f);
        p.lineTo(16.5f, 5.5f);
        p.lineTo(14.5f, 12.5f);
        p.lineTo(21f, 12.5f);
        p.lineTo(22f, 14.5f);
        p.lineTo(14.5f, 14.5f);
        p.lineTo(12.5f, 19.5f);
        p.lineTo(10.5f, 19.5f);
        p.lineTo(10.5f, 14.5f);
        p.lineTo(4.5f, 17.5f);
        p.closePath();
        g.draw(p);
    }

    private static void drawCircle(Graphics2D g, int x, int y, int radius) { g.drawOval(x - radius, y - radius, radius * 2, radius * 2); }
}
