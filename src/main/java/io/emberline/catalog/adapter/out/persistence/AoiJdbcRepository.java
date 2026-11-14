package io.emberline.catalog.adapter.out.persistence;

import io.emberline.catalog.application.port.out.AoiReadPort;
import io.emberline.catalog.domain.Aoi;
import io.emberline.catalog.domain.AoiLevel;
import io.emberline.catalog.domain.Bbox;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.sql.SqlResource;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads catalog partitions of one release; database errors leave as 503 data_not_loaded (no SQL in the problem). */
@Repository
class AoiJdbcRepository implements AoiReadPort {

    private final JdbcClient jdbc;

    AoiJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Aoi> all(long releaseId) {
        try {
            return jdbc.sql(SqlResource.text("catalog/select_aois.sql")).param("releaseId", releaseId).query((rs, n) -> {
                double west = rs.getDouble("bbox_west");
                Bbox bbox = rs.wasNull() ? null
                        : new Bbox(west, rs.getDouble("bbox_south"), rs.getDouble("bbox_east"), rs.getDouble("bbox_north"));
                return new Aoi(rs.getString("aoi_id"), rs.getString("name_ko"), rs.getString("name_en"),
                        AoiLevel.ofCode(rs.getString("level")).orElseThrow(), rs.getString("level_file"),
                        rs.getString("parent_id"), rs.getInt("n_cells"), rs.getBoolean("hidden"), rs.getBoolean("has_data"),
                        rs.getString("first_month"), rs.getString("last_month"), bbox);
            }).list();
        } catch (DataAccessException e) {
            throw new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, null, "the area catalog is not available", e);
        }
    }

    @Override
    public Optional<String> boundaryGeometry(long releaseId, String aoiId) {
        try {
            return jdbc.sql(SqlResource.text("catalog/select_boundary.sql")).param("releaseId", releaseId)
                    .param("aoiId", aoiId).query(String.class).optional();
        } catch (DataAccessException e) {
            throw new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, null, "area boundaries are not available", e);
        }
    }
}
