package io.emberline.fireinfo.adapter.in.web;

import io.emberline.fireinfo.application.port.in.LayerCsv;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * text/csv for the fire-info layers ({@link LayerCsv}: /fire-events, /aois/{aoiId}/fwi): UTF-8 BOM, the contract
 * {@code x-csv-columns} header, one line per row ({@code \n}), empty cell for null, numbers as stored, formula-guarded
 * text (a leading {@code = + - @}, tab or CR gets {@code '}), RFC 4180 quoting, and a download file name.
 */
class LayerCsvHttpMessageConverter extends AbstractHttpMessageConverter<LayerCsv> {

    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    LayerCsvHttpMessageConverter() {
        super(StandardCharsets.UTF_8, new MediaType("text", "csv"));
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return LayerCsv.class.isAssignableFrom(clazz);
    }

    @Override
    protected boolean canRead(MediaType mediaType) {
        return false;
    }

    @Override
    protected LayerCsv readInternal(Class<? extends LayerCsv> clazz, HttpInputMessage inputMessage) {
        throw new HttpMessageNotReadableException("CSV is not accepted as input", inputMessage);
    }

    @Override
    protected MediaType getDefaultContentType(LayerCsv body) {
        return TEXT_CSV;
    }

    @Override
    protected void writeInternal(LayerCsv body, HttpOutputMessage out) throws IOException {
        out.getHeaders().setContentType(TEXT_CSV);
        out.getHeaders().setContentDisposition(ContentDisposition.attachment()
                .filename(body.csvFileName().replaceAll("[^A-Za-z0-9_.-]", "-")).build());
        body.csvHeaders().forEach((name, value) -> out.getHeaders().set(name, value));
        Writer w = new OutputStreamWriter(out.getBody(), StandardCharsets.UTF_8);
        w.write('\uFEFF');
        line(w, body.csvHeader());
        for (List<Object> row : body.csvRows()) {
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
            return d.isNaN() || d.isInfinite() ? "" : BigDecimal.valueOf(d).toPlainString();
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof TemporalAccessor) {
            return value.toString();
        }
        return text(value.toString());
    }

    static String text(String s) {
        String guarded = s.isEmpty() || "=+-@\t\r".indexOf(s.charAt(0)) < 0 ? s : "'" + s;
        if (guarded.indexOf(',') >= 0 || guarded.indexOf('"') >= 0 || guarded.indexOf('\n') >= 0 || guarded.indexOf('\r') >= 0) {
            return "\"" + guarded.replace("\"", "\"\"") + "\"";
        }
        return guarded;
    }
}
