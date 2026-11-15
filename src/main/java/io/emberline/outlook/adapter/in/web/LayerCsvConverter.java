package io.emberline.outlook.adapter.in.web;

import io.emberline.outlook.application.port.in.LayerBody;
import io.emberline.outlook.application.port.in.OccurrenceCsv;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;

/**
 * text/csv for the outlook layer bodies: UTF-8 BOM, the contract's {@code x-csv-columns} header, one line per row
 * ({@code \n}), RFC 4180 quoting, formula-guarded text, empty cell for null, {@code Content-Disposition: attachment}.
 * Numbers are the stored values, unrounded: a value that is an exact float32 (every probability of the release) is
 * written as that float's shortest decimal, as the file shows it; any other double as its own shortest decimal.
 */
@Component
class LayerCsvConverter extends AbstractHttpMessageConverter<LayerBody> {

    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    LayerCsvConverter() {
        super(StandardCharsets.UTF_8, new MediaType("text", "csv"));
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return LayerBody.class.isAssignableFrom(clazz);
    }

    @Override
    protected boolean canRead(MediaType mediaType) {
        return false;
    }

    @Override
    protected LayerBody readInternal(Class<? extends LayerBody> clazz, HttpInputMessage inputMessage) {
        throw new HttpMessageNotReadableException("CSV is response-only", inputMessage);
    }

    @Override
    protected MediaType getDefaultContentType(LayerBody body) {
        return TEXT_CSV;
    }

    @Override
    protected void writeInternal(LayerBody body, HttpOutputMessage out) throws IOException {
        OccurrenceCsv table = body.csv().get();
        out.getHeaders().setContentType(TEXT_CSV);
        out.getHeaders().setContentDisposition(ContentDisposition.attachment().filename(table.fileName()).build());
        Writer w = new OutputStreamWriter(out.getBody(), StandardCharsets.UTF_8);
        w.write('\uFEFF');
        line(w, table.header());
        for (List<Object> row : table.rows()) {
            line(w, row);
        }
        w.flush();
    }

    private static void line(Writer w, List<?> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                w.write(',');
            }
            w.write(cell(cells.get(i)));
        }
        w.write('\n');
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double d) {
            return number(d);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return text(value.toString());
    }

    static String number(double d) {
        if (!Double.isFinite(d)) {
            return "";
        }
        float f = (float) d;
        BigDecimal exact = f == d ? new BigDecimal(Float.toString(f)) : BigDecimal.valueOf(d);
        return exact.toPlainString();
    }

    static String text(String s) {
        String guarded = s.isEmpty() || "=+-@\t\r".indexOf(s.charAt(0)) < 0 ? s : "'" + s;
        if (guarded.indexOf(',') >= 0 || guarded.indexOf('"') >= 0 || guarded.indexOf('\n') >= 0 || guarded.indexOf('\r') >= 0) {
            return "\"" + guarded.replace("\"", "\"\"") + "\"";
        }
        return guarded;
    }
}
