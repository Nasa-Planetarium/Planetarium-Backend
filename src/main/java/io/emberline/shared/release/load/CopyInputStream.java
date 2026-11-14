package io.emberline.shared.release.load;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Pulls rows from a streaming DuckDB result and encodes them as COPY text on demand (bounded memory). */
public final class CopyInputStream extends InputStream {

    /** Writes one result row as one COPY line. */
    @FunctionalInterface
    public interface RowEncoder {
        void encode(ResultSet row, CopyLine line) throws SQLException;
    }

    private static final int BATCH_ROWS = 4096;

    private final ResultSet rows;
    private final RowEncoder encoder;
    private final StringBuilder sb = new StringBuilder(1 << 20);
    private final CopyLine line = new CopyLine(sb);
    private byte[] buf = new byte[0];
    private int pos;
    private boolean done;
    private long count;

    public CopyInputStream(ResultSet rows, RowEncoder encoder) {
        this.rows = rows;
        this.encoder = encoder;
    }

    public long rowsRead() {
        return count;
    }

    private boolean fill() throws IOException {
        if (done) {
            return false;
        }
        sb.setLength(0);
        try {
            for (int i = 0; i < BATCH_ROWS; i++) {
                if (!rows.next()) {
                    done = true;
                    break;
                }
                encoder.encode(rows, line);
                line.end();
                count++;
            }
        } catch (SQLException e) {
            throw new IOException("reading release rows failed", e);
        }
        buf = sb.toString().getBytes(StandardCharsets.UTF_8);
        pos = 0;
        return buf.length > 0;
    }

    @Override
    public int read() throws IOException {
        if (pos >= buf.length && !fill()) {
            return -1;
        }
        return buf[pos++] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        while (pos >= buf.length) {
            if (!fill()) {
                return -1;
            }
        }
        int n = Math.min(len, buf.length - pos);
        System.arraycopy(buf, pos, b, off, n);
        pos += n;
        return n;
    }
}
