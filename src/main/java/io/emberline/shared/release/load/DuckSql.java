package io.emberline.shared.release.load;

import io.emberline.shared.sql.SqlResource;

/** DuckDB statements shared by loaders (src/main/resources/sql/shared). */
final class DuckSql {

    private DuckSql() {
    }

    static String text(String path) {
        return SqlResource.text(path);
    }
}
