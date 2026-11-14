package io.emberline.harmonized.adapter.out.parquet;

import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.release.ReleaseRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Files of the pinned release under EMBERLINE_RELEASE_ROOT (the folder ingest validated and hashed). The key is a
 * validated release_key, so the path cannot leave the root.
 */
@Component
public class ReleaseFiles {

    public static final String CELL_DAY = "harmonized_cell_day.parquet";
    public static final String RESIDUAL = "harmonized_residual_cell_month.parquet";
    public static final String BOUNDARIES = "aoi_boundaries.geojson";

    private final Optional<Path> root;

    public ReleaseFiles(EmberlineProperties properties) {
        this.root = properties.release().rootPath();
    }

    public Optional<Path> find(ReleaseRef release, String file) {
        return root.map(r -> r.resolve(release.releaseKey()).resolve(file).normalize())
                .filter(p -> p.startsWith(root.get()) && Files.isRegularFile(p));
    }

    public Path require(ReleaseRef release, String file) {
        return find(release, file).orElseThrow(() -> new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, null,
                "a file of the active release is not available on this server"));
    }
}
