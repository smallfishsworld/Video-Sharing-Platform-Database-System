package io.sustc.service.impl.support;

import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.support.AbstractSqlTypeValue;

/** Conversions between JDBC arrays and Java arrays. */
public final class SqlArrays {

    private SqlArrays() {}

    public static long[] toLongArray(Array array) throws SQLException {
        if (array == null) {
            return new long[0];
        }
        Object[] values = (Object[]) array.getArray();
        long[] result = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = ((Number) values[i]).longValue();
        }
        return result;
    }

    public static String[] toStringArray(Array array) throws SQLException {
        if (array == null) {
            return new String[0];
        }
        Object[] values = (Object[]) array.getArray();
        String[] result = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (String) values[i];
        }
        return result;
    }

    public static List<Long> toLongList(Array array) throws SQLException {
        return Arrays.stream(toLongArray(array)).boxed().toList();
    }

    public static List<String> toStringList(Array array) throws SQLException {
        return List.of(toStringArray(array));
    }

    /** A {@code text[]} statement parameter. */
    public static SqlParameterValue textArray(String[] values) {
        return new SqlParameterValue(Types.ARRAY, new AbstractSqlTypeValue() {
            @Override
            protected Object createTypeValue(Connection con, int sqlType, String typeName) throws SQLException {
                return con.createArrayOf("text", values);
            }
        });
    }
}
