package io.emberline.observation.adapter.in.web;

import io.emberline.observation.application.port.in.CellFrpList;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;

/**
 * text/csv for GET /api/v1/cells/frp: the contract {@code x-csv-columns} [cell_id, cell_lat, cell_lon, date, frp_sum,
 * frp_snpp_equiv_sum, frp_snpp_equiv_max, n_points], one line per JSON {@code cells[]} item, numbers as the JSON
 * writes them (plain notation, empty cell for null), CRLF, plus a download file name.
 */
@Component
class FrpCsvConverter extends AbstractHttpMessageConverter<CellFrpList> {

    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    /** Column name → cell text, in contract order. */
    static final List<Column> COLUMNS = List.of(
            new Column("cell_id", c -> Long.toString(c.cellId())),
            new Column("cell_lat", c -> num(c.cellLat())),
            new Column("cell_lon", c -> num(c.cellLon())),
            new Column("date", CellFrpList.Cell::date),
            new Column("frp_sum", c -> num(c.frpSum())),
            new Column("frp_snpp_equiv_sum", c -> num(c.frpSnppEquivSum())),
            new Column("frp_snpp_equiv_max", c -> num(c.frpSnppEquivMax())),
            new Column("n_points", c -> Long.toString(c.nPoints())));

    record Column(String name, Function<CellFrpList.Cell, String> cell) {
    }

    FrpCsvConverter() {
        super(TEXT_CSV);
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return CellFrpList.class.isAssignableFrom(clazz);
    }

    @Override
    protected boolean canRead(MediaType mediaType) {
        return false;
    }

    @Override
    protected CellFrpList readInternal(Class<? extends CellFrpList> clazz, HttpInputMessage inputMessage) {
        throw new HttpMessageNotReadableException("CSV is response-only", inputMessage);
    }

    @Override
    protected void writeInternal(CellFrpList body, HttpOutputMessage outputMessage) throws IOException {
        outputMessage.getHeaders().setContentDisposition(ContentDisposition.attachment().filename(fileName(body)).build());
        Writer w = new OutputStreamWriter(outputMessage.getBody(), StandardCharsets.UTF_8);
        w.write(COLUMNS.stream().map(Column::name).collect(Collectors.joining(",")));
        w.write("\r\n");
        for (CellFrpList.Cell c : body.cells()) {
            w.write(COLUMNS.stream().map(col -> {
                String v = col.cell().apply(c);
                return v == null ? "" : v;
            }).collect(Collectors.joining(",")));
            w.write("\r\n");
        }
        w.flush();
    }

    /** {@code emberline_frp_<aoi_id or bbox>_<start>_<end>_<freq>.csv}, file-name safe. */
    static String fileName(CellFrpList body) {
        String area = body.aoiId() != null ? body.aoiId()
                : body.bbox().stream().map(FrpCsvConverter::num).collect(Collectors.joining("_"));
        return ("emberline_frp_" + area + "_" + body.start() + "_" + body.end() + "_" + body.freq())
                .replaceAll("[^A-Za-z0-9_.-]", "-") + ".csv";
    }

    static String num(Number n) {
        if (n == null) {
            return null;
        }
        BigDecimal d = n instanceof Float f ? new BigDecimal(Float.toString(f)) : BigDecimal.valueOf(n.doubleValue());
        return d.toPlainString();
    }
}
