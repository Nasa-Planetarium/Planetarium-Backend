package io.emberline.shared.release.load;

/**
 * Builds PostgreSQL COPY text-format lines (tab separated, {@code \N} = NULL). Numbers are written with Java's
 * shortest round-trip form, so PostgreSQL stores exactly the file's double/float values.
 */
public final class CopyLine {

    private final StringBuilder sb;
    private boolean first = true;

    CopyLine(StringBuilder sb) {
        this.sb = sb;
    }

    /** A line writer appending to {@code sb}; the caller ends the line with a newline. */
    public static CopyLine into(StringBuilder sb) {
        return new CopyLine(sb);
    }

    private void sep() {
        if (!first) {
            sb.append('\t');
        }
        first = false;
    }

    public CopyLine text(String v) {
        sep();
        if (v == null) {
            sb.append("\\N");
            return this;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else {
                sb.append(c);
            }
        }
        return this;
    }

    public CopyLine num(double v, boolean isNull) {
        sep();
        if (isNull) {
            sb.append("\\N");
        } else {
            sb.append(Double.toString(v));
        }
        return this;
    }

    public CopyLine real(float v, boolean isNull) {
        sep();
        if (isNull) {
            sb.append("\\N");
        } else {
            sb.append(Float.toString(v));
        }
        return this;
    }

    public CopyLine integer(long v, boolean isNull) {
        sep();
        if (isNull) {
            sb.append("\\N");
        } else {
            sb.append(v);
        }
        return this;
    }

    public CopyLine bool(boolean v, boolean isNull) {
        sep();
        sb.append(isNull ? "\\N" : v ? "t" : "f");
        return this;
    }

    void end() {
        sb.append('\n');
        first = true;
    }
}
