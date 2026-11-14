package io.emberline.harmonized.adapter.in.web;

import io.emberline.harmonized.application.port.in.CsvTable;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * text/csv for {@link CsvTable} results: UTF-8 BOM, header row, one line per row ({@code \n}), formula-guarded text
 * cells, and {@code Content-Disposition: attachment} with the server-made file name.
 */
class CsvTableHttpMessageConverter extends AbstractHttpMessageConverter<CsvTable> {

    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    CsvTableHttpMessageConverter() {
        super(StandardCharsets.UTF_8, new MediaType("text", "csv"));
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return CsvTable.class.isAssignableFrom(clazz);
    }

    @Override
    protected boolean canRead(MediaType mediaType) {
        return false;
    }

    @Override
    protected CsvTable readInternal(Class<? extends CsvTable> clazz, HttpInputMessage inputMessage) {
        throw new HttpMessageNotReadableException("CSV is not accepted as input", inputMessage);
    }

    @Override
    protected MediaType getDefaultContentType(CsvTable table) {
        return TEXT_CSV;
    }

    @Override
    protected void writeInternal(CsvTable table, HttpOutputMessage out) throws IOException {
        out.getHeaders().setContentType(TEXT_CSV);
        out.getHeaders().setContentDisposition(ContentDisposition.attachment()
                .filename(CsvTable.safeName(table.csvFileName())).build());
        Writer w = new OutputStreamWriter(out.getBody(), StandardCharsets.UTF_8);
        w.write('\uFEFF');
        line(w, List.copyOf(table.csvHeader()));
        for (List<Object> row : table.csvRows()) {
            line(w, row);
        }
        w.flush();
    }

    private static void line(Writer w, List<?> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                w.write(',');
            }
            w.write(CsvCells.cell(cells.get(i)));
        }
        w.write('\n');
    }
}
