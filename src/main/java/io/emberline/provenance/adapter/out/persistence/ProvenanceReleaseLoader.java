package io.emberline.provenance.adapter.out.persistence;

import io.emberline.shared.release.load.CodeValues;
import io.emberline.shared.release.load.ColumnKind;
import io.emberline.shared.release.load.ColumnSpec;
import io.emberline.shared.release.load.CopyInputStream;
import io.emberline.shared.release.load.CopyLine;
import io.emberline.shared.release.load.DuckDb;
import io.emberline.shared.release.load.FileChecks;
import io.emberline.shared.release.load.LoadFailures;
import io.emberline.shared.release.load.LoadResult;
import io.emberline.shared.release.load.ParquetColumns;
import io.emberline.shared.release.load.Partitions;
import io.emberline.shared.release.load.ReleaseLoadException;
import io.emberline.shared.release.load.ReleaseLoader;
import io.emberline.shared.release.load.ReleaseSource;
import io.emberline.shared.release.load.ValidationReport;
import io.emberline.shared.sql.SqlResource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * provenance partitions: {@code method_meta} (m2v_meta.json text as-is + this ingest's aliases/warnings/files),
 * {@code month_status} (WORLD rows of aoi_month.parquet: value_source, data_source, status per month, copied) and
 * {@code satellite_day} (satellite_day_status.parquet when the release ships it; v1.2 names the day {@code date_utc} and
 * adds {@code note}) and {@code satellite_month} (satellite_month_status.parquet, v1.2, when the release ships it).
 */
@Component
class ProvenanceReleaseLoader implements ReleaseLoader {

    static final String META = "m2v_meta.json";
    static final String SDS = "satellite_day_status.parquet";
    static final String SMS = "satellite_month_status.parquet";
    static final String AOI_MONTH = "aoi_month.parquet";
    static final String AOI_LIST = "aoi_list.parquet";
    static final String HOTSPOTS_DIR = "harmonized_hotspots";
    static final String STATUS_AOI = "WORLD";
    static final String T_META = "provenance.method_meta";
    static final String T_MONTH = "provenance.month_status";
    static final String T_DAY = "provenance.satellite_day";
    static final String T_SAT_MONTH = "provenance.satellite_month";
    static final String METHOD_VERSION_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";

    static final List<ColumnSpec> MONTH_COLUMNS = List.of(
            ColumnSpec.required("aoi_id", ColumnKind.TEXT),
            ColumnSpec.required("year", ColumnKind.INTEGER),
            ColumnSpec.required("month", ColumnKind.INTEGER),
            ColumnSpec.required("value_source", ColumnKind.TEXT),
            ColumnSpec.required("data_source", ColumnKind.TEXT, "source_flag"),
            ColumnSpec.required("status", ColumnKind.TEXT));
    static final List<ColumnSpec> SDS_COLUMNS = List.of(
            ColumnSpec.required("satellite", ColumnKind.TEXT),
            ColumnSpec.required("date", ColumnKind.DATE, "date_utc"),
            ColumnSpec.required("status", ColumnKind.TEXT),
            ColumnSpec.optional("data_source", ColumnKind.TEXT),
            ColumnSpec.optional("updated_at", ColumnKind.TIMESTAMP),
            ColumnSpec.optional("note", ColumnKind.TEXT));
    /** Day statuses: the hand-off month codes plus v1.2 {@code low} (ok, low, missing, partial in the real file). */
    static final Set<String> SDS_STATUSES = Stream.concat(CodeValues.STATUSES.stream(), Stream.of("low")).collect(Collectors.toUnmodifiableSet());
    static final List<ColumnSpec> SMS_COLUMNS = List.of(
            ColumnSpec.required("satellite", ColumnKind.TEXT),
            ColumnSpec.required("year", ColumnKind.INTEGER),
            ColumnSpec.required("month", ColumnKind.INTEGER),
            ColumnSpec.optional("obs_days", ColumnKind.INTEGER),
            ColumnSpec.optional("low_days", ColumnKind.INTEGER),
            ColumnSpec.optional("missing_days", ColumnKind.INTEGER),
            ColumnSpec.optional("first_day", ColumnKind.DATE),
            ColumnSpec.optional("last_day", ColumnKind.DATE),
            ColumnSpec.optional("n_det_ea", ColumnKind.INTEGER),
            ColumnSpec.optional("days_in_month", ColumnKind.INTEGER),
            ColumnSpec.required("month_status", ColumnKind.TEXT));
    static final Set<String> SMS_STATUSES = Set.of("complete", "partial");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DuckDb duckDb;
    private final Partitions partitions;

    ProvenanceReleaseLoader(DuckDb duckDb, Partitions partitions) {
        this.duckDb = duckDb;
        this.partitions = partitions;
    }

    @Override
    public String context() {
        return "provenance";
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public List<ReleaseFile> files() {
        return List.of(new ReleaseFile(META, true), new ReleaseFile(SDS, false), new ReleaseFile(SMS, false),
                new ReleaseFile(AOI_MONTH, true), new ReleaseFile(AOI_LIST, true));
    }

    @Override
    public void validate(ReleaseSource source) {
        ValidationReport report = source.report();
        try {
            JsonNode meta = JSON.readTree(Files.readString(source.file(META)));
            JsonNode mv = meta.isObject() ? meta.get("method_version") : null;
            if (mv == null || !mv.isString() || !mv.asString().matches(METHOD_VERSION_PATTERN)) {
                report.reject("META_INVALID", META, "method_version");
            }
        } catch (IOException | RuntimeException e) {
            report.reject("META_INVALID", META, "not JSON");
        }
        if (source.has(SMS)) {
            try (Connection duck = duckDb.open()) {
                ParquetColumns c = ParquetColumns.inspect(duck, source.file(SMS).toString(), SMS, SMS_COLUMNS, report);
                if (c.ok()) {
                    FileChecks.codeValues(duck, source.file(SMS).toString(), SMS, c, "month_status", SMS_STATUSES, Map.of(), report);
                    FileChecks.uniqueKey(duck, source.file(SMS).toString(), SMS, c, "{satellite}, {year}, {month}", report);
                }
            } catch (SQLException e) {
                report.reject("FILE_UNREADABLE", SMS, null);
            }
        }
        if (!source.has(SDS)) {
            report.warn("FILE_ABSENT", SDS, "satellite-status answers month level only");
            return;
        }
        try (Connection duck = duckDb.open()) {
            ParquetColumns c = ParquetColumns.inspect(duck, source.file(SDS).toString(), SDS, SDS_COLUMNS, report);
            if (c.ok()) {
                FileChecks.codeValues(duck, source.file(SDS).toString(), SDS, c, "status", SDS_STATUSES, Map.of(), report);
                FileChecks.uniqueKey(duck, source.file(SDS).toString(), SDS, c, "{satellite}, {date}", report);
            }
        } catch (SQLException e) {
            report.reject("FILE_UNREADABLE", SDS, null);
        }
    }

    @Override
    public LoadResult load(ReleaseSource source) {
        long id = source.releaseId();
        Map<String, Long> rows = new LinkedHashMap<>();
        try (Connection duck = duckDb.open()) {
            ValidationReport scratch = new ValidationReport();
            long aoiCount = FileChecks.count(duck, source.file(AOI_LIST).toString(), SqlResource.text("provenance/duck_count.sql"));
            Map<String, Object> files = new LinkedHashMap<>();
            files.put("hotspots", Files.isDirectory(source.file(HOTSPOTS_DIR)));
            files.put("satellite_day_status", source.has(SDS));
            if (source.has(SMS)) {
                files.put("satellite_month_status", true);
            }
            files.put("aoi_count", aoiCount);
            files.put("month_status_from", STATUS_AOI + " rows of " + AOI_MONTH);
            StringBuilder meta = new StringBuilder();
            new CopyLineWriter(meta).line(l -> l.text(source.methodVersion()).text(read(source))
                    .text(JSON.writeValueAsString(source.report().aliases()))
                    .text(JSON.writeValueAsString(source.report().warnings()))
                    .text(JSON.writeValueAsString(files)));
            rows.put(T_META, partitions.load(T_META, id, SqlResource.text("provenance/copy_method_meta.sql"),
                    new ByteArrayInputStream(meta.toString().getBytes(StandardCharsets.UTF_8)),
                    SqlResource.statements("provenance/index_method_meta.sql")));

            ParquetColumns m = ParquetColumns.inspect(duck, source.file(AOI_MONTH).toString(), AOI_MONTH, MONTH_COLUMNS, scratch);
            partitions.create(T_MONTH, id);
            try (PreparedStatement ps = duck.prepareStatement(m.render(SqlResource.text("provenance/duck_month_status.sql")))) {
                ps.setString(1, source.file(AOI_MONTH).toString());
                ps.setString(2, STATUS_AOI);
                try (ResultSet rs = ps.executeQuery()) {
                    rows.put(T_MONTH, partitions.copy(T_MONTH, id, SqlResource.text("provenance/copy_month_status.sql"),
                            new CopyInputStream(rs, (r, l) -> l.integer(r.getInt(1), false).integer(r.getInt(2), false)
                                    .text(CodeValues.valueSource(r.getString(3))).text(r.getString(4)).text(r.getString(5)))));
                }
            }
            partitions.finish(T_MONTH, id, SqlResource.statements("provenance/index_month_status.sql"));
            if (rows.get(T_MONTH) == 0) {
                source.report().warn("MONTH_STATUS_EMPTY", AOI_MONTH, "no " + STATUS_AOI + " rows");
            }

            partitions.create(T_DAY, id);
            long days = 0;
            if (source.has(SDS)) {
                ParquetColumns s = ParquetColumns.inspect(duck, source.file(SDS).toString(), SDS, SDS_COLUMNS, scratch);
                try (PreparedStatement ps = duck.prepareStatement(s.render(SqlResource.text("provenance/duck_satellite_day.sql")))) {
                    ps.setString(1, source.file(SDS).toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        days = partitions.copy(T_DAY, id, SqlResource.text("provenance/copy_satellite_day.sql"),
                                new CopyInputStream(rs, (r, l) -> l.text(r.getString(1)).text(String.valueOf(r.getObject(2)))
                                        .text(r.getString(3)).text(r.getString(4))
                                        .text(r.getObject(5, OffsetDateTime.class) == null ? null : r.getObject(5, OffsetDateTime.class).toString())
                                        .text(r.getString(6))));
                    }
                }
            }
            partitions.finish(T_DAY, id, SqlResource.statements("provenance/index_satellite_day.sql"));
            rows.put(T_DAY, days);

            // only a release that ships satellite_month_status gets a satellite_month partition (v1.1: as before)
            if (source.has(SMS)) {
                partitions.create(T_SAT_MONTH, id);
                long satMonths;
                ParquetColumns sm = ParquetColumns.inspect(duck, source.file(SMS).toString(), SMS, SMS_COLUMNS, scratch);
                try (PreparedStatement ps = duck.prepareStatement(sm.render(SqlResource.text("provenance/duck_satellite_month.sql")))) {
                    ps.setString(1, source.file(SMS).toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        satMonths = partitions.copy(T_SAT_MONTH, id, SqlResource.text("provenance/copy_satellite_month.sql"),
                                new CopyInputStream(rs, (r, l) -> l.text(r.getString(1)).integer(r.getInt(2), false)
                                        .integer(r.getInt(3), false).integer(r.getLong(4), r.wasNull())
                                        .integer(r.getLong(5), r.wasNull()).integer(r.getLong(6), r.wasNull())
                                        .text(r.getString(7)).text(r.getString(8)).integer(r.getLong(9), r.wasNull())
                                        .integer(r.getLong(10), r.wasNull()).text(r.getString(11))));
                    }
                }
                partitions.finish(T_SAT_MONTH, id, SqlResource.statements("provenance/index_satellite_month.sql"));
                rows.put(T_SAT_MONTH, satMonths);
            }
            return new LoadResult(rows);
        } catch (SQLException e) {
            throw new ReleaseLoadException(LoadFailures.reason(e, "provenance"), e);
        } catch (java.io.UncheckedIOException e) {
            throw new ReleaseLoadException("FILE_UNREADABLE:" + META, e);
        }
    }

    private static String read(ReleaseSource source) {
        try {
            return Files.readString(source.file(META));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override
    public void drop(long releaseId) {
        try {
            partitions.drop(T_META, releaseId);
            partitions.drop(T_MONTH, releaseId);
            partitions.drop(T_DAY, releaseId);
            partitions.drop(T_SAT_MONTH, releaseId);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot drop provenance partitions", e);
        }
    }

    /** Builds COPY lines into a StringBuilder (small tables). */
    record CopyLineWriter(StringBuilder sb) {
        void line(java.util.function.Consumer<CopyLine> fields) {
            CopyLine l = CopyLine.into(sb);
            fields.accept(l);
            sb.append('\n');
        }
    }
}
