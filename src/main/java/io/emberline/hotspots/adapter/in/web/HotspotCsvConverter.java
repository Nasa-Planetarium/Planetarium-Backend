package io.emberline.hotspots.adapter.in.web;

import io.emberline.hotspots.application.port.in.HotspotItem;
import io.emberline.hotspots.application.port.in.HotspotListResponse;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * text/csv for GET /api/v1/hotspots: the points of the page, same 19 columns and order as the JSON items (contract
 * x-csv-columns), RFC 4180 quoting, empty cell for null, plus a download file name. The JSON page fields that are not
 * point columns travel as headers: {@code X-Truncated} (= {@code truncated}), {@code X-Total-Count} (= {@code total})
 * and, when more points follow, {@code X-Next-Cursor} (= {@code next_cursor}).
 */
@Component
class HotspotCsvConverter extends AbstractHttpMessageConverter<HotspotListResponse> {

    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    static final String TRUNCATED = "X-Truncated";
    static final String TOTAL_COUNT = "X-Total-Count";
    static final String NEXT_CURSOR = "X-Next-Cursor";

    /** Column name → cell text, in contract order. */
    static final List<Column> COLUMNS = List.of(
            new Column("latitude", h -> num(h.latitude())),
            new Column("longitude", h -> num(h.longitude())),
            new Column("acq_datetime_utc", HotspotItem::acqDatetimeUtc),
            new Column("date_local", HotspotItem::dateLocal),
            new Column("satellite", HotspotItem::satellite),
            new Column("instrument", HotspotItem::instrument),
            new Column("frp", h -> num(h.frp())),
            new Column("confidence", HotspotItem::confidence),
            new Column("daynight", HotspotItem::daynight),
            new Column("cell_id", h -> Long.toString(h.cellId())),
            new Column("adm0_a3", HotspotItem::adm0A3),
            new Column("adm1_code", HotspotItem::adm1Code),
            new Column("harmonized_weight", h -> num(h.harmonizedWeight())),
            new Column("weight_lo", h -> num(h.weightLo())),
            new Column("weight_hi", h -> num(h.weightHi())),
            new Column("weight_source", HotspotItem::weightSource),
            new Column("is_reference", h -> Boolean.toString(h.isReference())),
            new Column("source", HotspotItem::source),
            new Column("method_version", HotspotItem::methodVersion));

    record Column(String name, Function<HotspotItem, String> cell) {
    }

    HotspotCsvConverter() {
        super(TEXT_CSV);
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return HotspotListResponse.class.isAssignableFrom(clazz);
    }

    @Override
    protected boolean canRead(MediaType mediaType) {
        return false;
    }

    @Override
    protected HotspotListResponse readInternal(Class<? extends HotspotListResponse> clazz, HttpInputMessage inputMessage) {
        throw new HttpMessageNotReadableException("CSV is response-only", inputMessage);
    }

    @Override
    protected void writeInternal(HotspotListResponse body, HttpOutputMessage outputMessage) throws IOException {
        outputMessage.getHeaders().setContentDisposition(ContentDisposition.attachment().filename(fileName(body)).build());
        outputMessage.getHeaders().set(TRUNCATED, Boolean.toString(body.truncated()));
        outputMessage.getHeaders().set(TOTAL_COUNT, Long.toString(body.total()));
        if (body.nextCursor() != null) {
            outputMessage.getHeaders().set(NEXT_CURSOR, body.nextCursor());
        }
        Writer w = new OutputStreamWriter(outputMessage.getBody(), StandardCharsets.UTF_8);
        w.write(COLUMNS.stream().map(Column::name).collect(Collectors.joining(",")));
        w.write("\r\n");
        List<String> cells = new ArrayList<>(COLUMNS.size());
        for (HotspotItem h : body.hotspots()) {
            cells.clear();
            for (Column c : COLUMNS) {
                cells.add(quote(c.cell().apply(h)));
            }
            w.write(String.join(",", cells));
            w.write("\r\n");
        }
        w.flush();
    }

    /** {@code emberline_hotspots_<aoi_id or bbox>_<start>_<end>.csv}, file-name safe. */
    static String fileName(HotspotListResponse body) {
        String area = body.aoiId() != null ? body.aoiId()
                : body.bbox().stream().map(HotspotCsvConverter::num).collect(Collectors.joining("_"));
        return ("emberline_hotspots_" + area + "_" + body.start() + "_" + body.end()).replaceAll("[^A-Za-z0-9_.-]", "-") + ".csv";
    }

    private static String num(Number n) {
        if (n == null) {
            return null;
        }
        BigDecimal d = n instanceof Float f ? new BigDecimal(Float.toString(f)) : BigDecimal.valueOf(n.doubleValue());
        return d.toPlainString();
    }

    private static String quote(String v) {
        if (v == null) {
            return "";
        }
        return v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")
                ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }
}
