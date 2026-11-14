package io.emberline.catalog.adapter.out.persistence;

import io.emberline.catalog.domain.AoiLevel;
import io.emberline.shared.release.load.ColumnKind;
import io.emberline.shared.release.load.ColumnSpec;
import io.emberline.shared.release.load.CopyInputStream;
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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.locationtech.jts.geom.Envelope;
import org.springframework.stereotype.Component;

/**
 * catalog partitions: {@code catalog.aoi} from aoi_list.parquet (levels translated, file level kept; has_data and
 * first/last month from aoi_month.parquet; bbox from the boundary) and {@code catalog.aoi_boundary} from
 * aoi_boundaries.geojson (geometry stored as-is, PostGIS deferred).
 */
@Component
class CatalogReleaseLoader implements ReleaseLoader {

    static final String AOI_LIST = "aoi_list.parquet";
    static final String BOUNDARIES = "aoi_boundaries.geojson";
    static final String AOI_MONTH = "aoi_month.parquet";
    static final String AOI = "catalog.aoi";
    static final String AOI_BOUNDARY = "catalog.aoi_boundary";

    static final List<ColumnSpec> LIST_COLUMNS = List.of(
            ColumnSpec.required("aoi_id", ColumnKind.TEXT),
            ColumnSpec.required("name_ko", ColumnKind.TEXT, "name"),
            ColumnSpec.optional("name_en", ColumnKind.TEXT),
            ColumnSpec.required("level", ColumnKind.TEXT),
            ColumnSpec.required("parent_id", ColumnKind.TEXT, "parent_aoi"),
            ColumnSpec.required("n_cells", ColumnKind.INTEGER),
            ColumnSpec.required("hidden", ColumnKind.BOOLEAN));
    static final List<ColumnSpec> MONTH_KEY_COLUMNS = List.of(
            ColumnSpec.required("aoi_id", ColumnKind.TEXT),
            ColumnSpec.required("year", ColumnKind.INTEGER),
            ColumnSpec.required("month", ColumnKind.INTEGER));

    private final DuckDb duckDb;
    private final Partitions partitions;

    CatalogReleaseLoader(DuckDb duckDb, Partitions partitions) {
        this.duckDb = duckDb;
        this.partitions = partitions;
    }

    @Override
    public String context() {
        return "catalog";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public List<ReleaseFile> files() {
        return List.of(new ReleaseFile(AOI_LIST, true), new ReleaseFile(BOUNDARIES, true), new ReleaseFile(AOI_MONTH, true));
    }

    @Override
    public void validate(ReleaseSource source) {
        ValidationReport report = source.report();
        String list = source.file(AOI_LIST).toString();
        try (Connection duck = duckDb.open()) {
            ParquetColumns cols = ParquetColumns.inspect(duck, list, AOI_LIST, LIST_COLUMNS, report);
            if (cols.ok()) {
                Map<String, String> fileAliases = new HashMap<>();
                AoiLevel.FILE_ALIASES.forEach((k, v) -> fileAliases.put(k, v.code()));
                FileChecks.codeValues(duck, list, AOI_LIST, cols, "level", levelCodes(), fileAliases, report);
                FileChecks.uniqueKey(duck, list, AOI_LIST, cols, "{aoi_id}", report);
                checkRows(duck, list, cols, report);
            }
        } catch (SQLException e) {
            report.reject("FILE_UNREADABLE", AOI_LIST, null);
        }
        try {
            GeoJsonBoundaries.Parsed parsed = GeoJsonBoundaries.read(source.file(BOUNDARIES));
            parsed.problems().stream().limit(5).forEach(p -> report.reject("GEOJSON_INVALID", BOUNDARIES, p.replaceAll("[^A-Za-z0-9_ .-]", "?")));
        } catch (IOException e) {
            report.reject("FILE_UNREADABLE", BOUNDARIES, null);
        }
    }

    private static Set<String> levelCodes() {
        Set<String> codes = new java.util.HashSet<>();
        for (AoiLevel l : AoiLevel.values()) {
            codes.add(l.code());
        }
        return codes;
    }

    private static void checkRows(Connection duck, String list, ParquetColumns cols, ValidationReport report) throws SQLException {
        try (PreparedStatement ps = duck.prepareStatement(cols.render(SqlResource.text("catalog/duck_aoi_checks.sql")))) {
            ps.setString(1, list);
            ps.setString(2, list);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getLong("bad_id") > 0) {
                    report.reject("AOI_ID_INVALID", AOI_LIST, rs.getLong("bad_id") + " rows");
                }
                if (rs.getLong("outside_contract_id") > 0) {
                    report.warn("AOI_ID_OUTSIDE_CONTRACT", AOI_LIST, rs.getLong("outside_contract_id") + " rows kept");
                }
                if (rs.getLong("bad_n_cells") > 0) {
                    report.reject("NEGATIVE_VALUE", AOI_LIST, "n_cells " + rs.getLong("bad_n_cells") + " rows");
                }
                if (rs.getLong("bad_hidden") > 0) {
                    report.reject("NULL_VALUE", AOI_LIST, "hidden " + rs.getLong("bad_hidden") + " rows");
                }
                if (rs.getLong("orphan_parent") > 0) {
                    report.warn("PARENT_UNKNOWN", AOI_LIST, rs.getLong("orphan_parent") + " rows");
                }
            }
        }
    }

    @Override
    public LoadResult load(ReleaseSource source) {
        long id = source.releaseId();
        try (Connection duck = duckDb.open()) {
            ValidationReport scratch = new ValidationReport();
            ParquetColumns monthCols = ParquetColumns.inspect(duck, source.file(AOI_MONTH).toString(), AOI_MONTH, MONTH_KEY_COLUMNS, scratch);
            Map<String, int[]> months = months(duck, source.file(AOI_MONTH).toString(), monthCols);
            GeoJsonBoundaries.Parsed parsed = GeoJsonBoundaries.read(source.file(BOUNDARIES));
            Map<String, GeoJsonBoundaries.Boundary> boundaries = parsed.boundaries();
            ParquetColumns cols = ParquetColumns.inspect(duck, source.file(AOI_LIST).toString(), AOI_LIST, LIST_COLUMNS, scratch);
            Map<String, Long> rows = new LinkedHashMap<>();
            partitions.create(AOI, id);
            try (PreparedStatement ps = duck.prepareStatement(cols.render(SqlResource.text("catalog/duck_aoi_list.sql")))) {
                ps.setString(1, source.file(AOI_LIST).toString());
                try (ResultSet rs = ps.executeQuery()) {
                    CopyInputStream in = new CopyInputStream(rs, (row, line) -> {
                        String aoiId = row.getString("aoi_id");
                        String fileLevel = row.getString("level");
                        AoiLevel level = AoiLevel.ofFile(fileLevel).orElseThrow();
                        int[] m = months.get(aoiId);
                        Optional<Envelope> bbox = Optional.ofNullable(boundaries.get(aoiId)).map(GeoJsonBoundaries.Boundary::bbox);
                        line.text(aoiId).text(row.getString("name_ko")).text(row.getString("name_en")).text(level.code())
                                .text(fileLevel).integer(level.rank(), false).text(row.getString("parent_id"))
                                .integer(row.getLong("n_cells"), false).bool(row.getBoolean("hidden"), false)
                                .bool(m != null, false).text(m == null ? null : ym(m[0])).text(m == null ? null : ym(m[1]))
                                .num(bbox.map(Envelope::getMinX).orElse(0.0), bbox.isEmpty())
                                .num(bbox.map(Envelope::getMinY).orElse(0.0), bbox.isEmpty())
                                .num(bbox.map(Envelope::getMaxX).orElse(0.0), bbox.isEmpty())
                                .num(bbox.map(Envelope::getMaxY).orElse(0.0), bbox.isEmpty());
                    });
                    rows.put(AOI, partitions.copy(AOI, id, SqlResource.text("catalog/copy_aoi.sql"), in));
                }
            }
            partitions.finish(AOI, id, SqlResource.statements("catalog/index_aoi.sql"));
            rows.put(AOI_BOUNDARY, partitions.load(AOI_BOUNDARY, id, SqlResource.text("catalog/copy_aoi_boundary.sql"),
                    boundaryRows(boundaries), SqlResource.statements("catalog/index_aoi_boundary.sql")));
            return new LoadResult(rows);
        } catch (SQLException e) {
            throw new ReleaseLoadException(LoadFailures.reason(e, "catalog"), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, int[]> months(Connection duck, String file, ParquetColumns cols) throws SQLException {
        Map<String, int[]> out = new HashMap<>();
        try (PreparedStatement ps = duck.prepareStatement(cols.render(SqlResource.text("catalog/duck_aoi_months.sql")))) {
            ps.setString(1, file);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), new int[] {rs.getInt(2), rs.getInt(3)});
                }
            }
        }
        return out;
    }

    private static String ym(int yyyymm) {
        return String.format("%04d-%02d", yyyymm / 100, yyyymm % 100);
    }

    private static ByteArrayInputStream boundaryRows(Map<String, GeoJsonBoundaries.Boundary> boundaries) {
        StringBuilder sb = new StringBuilder();
        for (GeoJsonBoundaries.Boundary b : boundaries.values()) {
            sb.append(escape(b.aoiId())).append('\t').append(b.type() == null ? "\\N" : b.type()).append('\t')
                    .append(b.geometryJson() == null ? "\\N" : escape(b.geometryJson())).append('\n');
        }
        return new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    @Override
    public void drop(long releaseId) {
        try {
            partitions.drop(AOI, releaseId);
            partitions.drop(AOI_BOUNDARY, releaseId);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot drop catalog partitions", e);
        }
    }
}
