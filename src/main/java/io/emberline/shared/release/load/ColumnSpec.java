package io.emberline.shared.release.load;

import java.util.List;

/**
 * One expected column: hand-off name, kind, whether required, and older names accepted as aliases
 * (e.g. aoi_list {@code name → name_ko}, {@code parent_aoi → parent_id}).
 */
public record ColumnSpec(String name, ColumnKind kind, boolean required, List<String> aliases) {

    public ColumnSpec {
        aliases = List.copyOf(aliases);
    }

    public static ColumnSpec required(String name, ColumnKind kind, String... aliases) {
        return new ColumnSpec(name, kind, true, List.of(aliases));
    }

    public static ColumnSpec optional(String name, ColumnKind kind, String... aliases) {
        return new ColumnSpec(name, kind, false, List.of(aliases));
    }
}
