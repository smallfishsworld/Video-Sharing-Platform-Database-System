package io.sustc.service.impl.importer;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import org.postgresql.copy.CopyIn;

/**
 * Encodes rows in PostgreSQL's COPY text format and streams them to a {@link CopyIn} in large
 * chunks.
 *
 * <p>Numbers are written as ASCII digits without allocating, so the 6M-row followings table costs
 * almost nothing on the Java side. Text values are escaped as the COPY text format requires
 * (backslash, tab, newline and carriage return). Floats are written as the decimal form of their
 * exact {@code double} value, so a {@code double precision} column stores exactly {@code (double)
 * f}, and comparisons against float parameters behave like Java float comparisons.
 */
final class CopyRowWriter {

    private static final int BUFFER_SIZE = 1 << 16;
    private static final byte[] NULL = {'\\', 'N'};
    private static final byte[] LONG_MIN = Long.toString(Long.MIN_VALUE).getBytes(StandardCharsets.US_ASCII);

    private final CopyIn copyIn;
    private final byte[] buffer = new byte[BUFFER_SIZE];
    private final byte[] digits = new byte[20];
    private int position;
    private boolean firstField = true;

    CopyRowWriter(CopyIn copyIn) {
        this.copyIn = copyIn;
    }

    CopyRowWriter value(long v) throws SQLException {
        separator();
        if (v == Long.MIN_VALUE) {
            write(LONG_MIN);
            return this;
        }
        if (v < 0) {
            put((byte) '-');
            v = -v;
        }
        int n = 0;
        do {
            digits[n++] = (byte) ('0' + v % 10);
            v /= 10;
        } while (v != 0);
        ensureCapacity(n);
        while (n > 0) {
            buffer[position++] = digits[--n];
        }
        return this;
    }

    CopyRowWriter value(Long v) throws SQLException {
        return v == null ? nullValue() : value(v.longValue());
    }

    CopyRowWriter value(float v) throws SQLException {
        return value((double) v);
    }

    /** {@link Double#toString} round-trips exactly through PostgreSQL's float8 input. */
    CopyRowWriter value(double v) throws SQLException {
        separator();
        writeAscii(Double.toString(v));
        return this;
    }

    CopyRowWriter value(Timestamp v) throws SQLException {
        if (v == null) {
            return nullValue();
        }
        separator();
        // Timestamp#toString uses the JVM time zone, matching PreparedStatement#setTimestamp.
        writeAscii(v.toString());
        return this;
    }

    CopyRowWriter value(String v) throws SQLException {
        if (v == null) {
            return nullValue();
        }
        separator();
        write(escape(v).getBytes(StandardCharsets.UTF_8));
        return this;
    }

    CopyRowWriter nullValue() throws SQLException {
        separator();
        write(NULL);
        return this;
    }

    void endRow() throws SQLException {
        put((byte) '\n');
        firstField = true;
    }

    /** Flushes the remaining bytes and finishes the COPY; returns the number of rows loaded. */
    long finish() throws SQLException {
        flush();
        return copyIn.endCopy();
    }

    private void separator() throws SQLException {
        if (firstField) {
            firstField = false;
        } else {
            put((byte) '\t');
        }
    }

    private static String escape(String s) {
        int i = 0;
        while (i < s.length() && !needsEscape(s.charAt(i))) {
            i++;
        }
        if (i == s.length()) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 8).append(s, 0, i);
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean needsEscape(char c) {
        return c == '\\' || c == '\t' || c == '\n' || c == '\r';
    }

    private void writeAscii(String s) throws SQLException {
        ensureCapacity(s.length());
        for (int i = 0; i < s.length(); i++) {
            buffer[position++] = (byte) s.charAt(i);
        }
    }

    private void write(byte[] bytes) throws SQLException {
        if (bytes.length > buffer.length) {
            flush();
            copyIn.writeToCopy(bytes, 0, bytes.length);
            return;
        }
        ensureCapacity(bytes.length);
        System.arraycopy(bytes, 0, buffer, position, bytes.length);
        position += bytes.length;
    }

    private void put(byte b) throws SQLException {
        ensureCapacity(1);
        buffer[position++] = b;
    }

    private void ensureCapacity(int n) throws SQLException {
        if (position + n > buffer.length) {
            flush();
        }
    }

    private void flush() throws SQLException {
        if (position > 0) {
            copyIn.writeToCopy(buffer, 0, position);
            position = 0;
        }
    }
}
