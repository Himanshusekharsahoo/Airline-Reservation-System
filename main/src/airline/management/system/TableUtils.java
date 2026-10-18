package airline.management.system;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Vector;
import javax.swing.table.DefaultTableModel;

/** Converts a JDBC result set into a non-editable Swing table model. */
public final class TableUtils {
    private TableUtils() { }

    public static DefaultTableModel toTableModel(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        Vector<String> columns = new Vector<>();
        for (int column = 1; column <= metadata.getColumnCount(); column++) columns.add(metadata.getColumnLabel(column));
        Vector<Vector<Object>> rows = new Vector<>();
        while (resultSet.next()) {
            Vector<Object> row = new Vector<>();
            for (int column = 1; column <= metadata.getColumnCount(); column++) row.add(resultSet.getObject(column));
            rows.add(row);
        }
        return new DefaultTableModel(rows, columns) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }
}
