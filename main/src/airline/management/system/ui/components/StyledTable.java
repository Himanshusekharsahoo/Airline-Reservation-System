package airline.management.system.ui.components;

import airline.management.system.ui.theme.AeroColors;
import airline.management.system.ui.theme.AeroConstants;
import airline.management.system.ui.theme.AeroFonts;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableModel;

public class StyledTable extends JTable {
    public StyledTable() {
        super();
        initStyle();
    }

    public StyledTable(TableModel model) {
        super(model);
        initStyle();
    }

    private void initStyle() {
        setRowHeight(AeroConstants.TABLE_ROW_HEIGHT);
        setFont(AeroFonts.BODY);
        setForeground(AeroColors.TEXT_MAIN);
        setSelectionBackground(AeroColors.TABLE_SELECTION);
        setSelectionForeground(AeroColors.TEXT_MAIN);
        setGridColor(AeroColors.BORDER);
        setShowVerticalLines(false);
        setShowHorizontalLines(true);
        setIntercellSpacing(new Dimension(0, 1));
        setFillsViewportHeight(true);
        setBackground(Color.WHITE);

        JTableHeader header = getTableHeader();
        header.setFont(AeroFonts.CAPTION_BOLD);
        header.setForeground(AeroColors.TEXT_SECONDARY);
        header.setBackground(AeroColors.TABLE_HEADER_BG);
        header.setPreferredSize(new Dimension(header.getPreferredSize().width, 40));
        header.setReorderingAllowed(false);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, AeroColors.BORDER));

        header.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                l.setBackground(AeroColors.TABLE_HEADER_BG);
                l.setForeground(AeroColors.TEXT_SECONDARY);
                l.setFont(AeroFonts.CAPTION_BOLD);
                l.setBorder(new EmptyBorder(8, 14, 8, 14));
                l.setHorizontalAlignment(SwingConstants.LEFT);
                return l;
            }
        });

        setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            private final StatusBadge badge = new StatusBadge("");
            private final javax.swing.JPanel badgeWrapper = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 8));

            {
                badgeWrapper.setOpaque(true);
                badgeWrapper.add(badge);
            }

            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                String colName = table.getColumnName(column).toLowerCase();
                String text = value == null ? "—" : String.valueOf(value).trim();
                if (text.isEmpty()) text = "—";

                if (colName.contains("status") || colName.contains("state") || colName.equals("payment") || colName.equals("severity") || colName.equals("priority")) {
                    badge.setStatus(text);
                    Color bg = isSelected ? AeroColors.TABLE_SELECTION : (row % 2 == 0 ? Color.WHITE : AeroColors.TABLE_ROW_ALT);
                    badgeWrapper.setBackground(bg);
                    return badgeWrapper;
                }

                Component c = super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column);
                setBorder(new EmptyBorder(6, 14, 6, 14));

                boolean isIdentifier = colName.contains("pnr") || colName.contains("flight") || colName.contains("gate")
                        || colName.contains("tag") || colName.contains("ref") || colName.contains("code") || colName.contains("transaction");
                boolean isNumeric = colName.contains("amount") || colName.contains("price") || colName.contains("fare")
                        || colName.contains("capacity") || colName.contains("balance");

                if (isIdentifier) {
                    setFont(AeroFonts.MONO_BOLD);
                } else {
                    setFont(AeroFonts.BODY);
                }

                if (isNumeric) {
                    setHorizontalAlignment(SwingConstants.RIGHT);
                } else {
                    setHorizontalAlignment(SwingConstants.LEFT);
                }

                if (isSelected) {
                    c.setBackground(AeroColors.TABLE_SELECTION);
                    c.setForeground(AeroColors.TEXT_MAIN);
                } else {
                    c.setBackground(row % 2 == 0 ? Color.WHITE : AeroColors.TABLE_ROW_ALT);
                    c.setForeground(AeroColors.TEXT_MAIN);
                }
                return c;
            }
        });
    }
}
