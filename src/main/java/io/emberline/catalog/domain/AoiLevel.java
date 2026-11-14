package io.emberline.catalog.domain;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AOI level as the hand-off names it (contract enum AoiLevel). The release file writes {@code kr / sido / sgg}; the
 * loader translates them ({@code x-release-values}) and keeps the file value next to it. Declaration order = list order.
 */
public enum AoiLevel {
    WORLD("world"), CONTINENT("continent"), COUNTRY("country"), ADM1("adm1"), KR_SIDO("kr_sido"), KR_SGG("kr_sgg"),
    GROUP("group");

    /** File value → hand-off level (only the values that differ). */
    public static final Map<String, AoiLevel> FILE_ALIASES = Map.of("kr", COUNTRY, "sido", KR_SIDO, "sgg", KR_SGG);
    private static final Map<String, AoiLevel> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(AoiLevel::code, Function.identity()));

    private final String code;

    AoiLevel(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public int rank() {
        return ordinal();
    }

    public static Optional<AoiLevel> ofCode(String code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }

    /** A level as written in aoi_list.parquet: a hand-off code or one of the file aliases. */
    public static Optional<AoiLevel> ofFile(String fileValue) {
        AoiLevel alias = FILE_ALIASES.get(fileValue);
        return alias != null ? Optional.of(alias) : ofCode(fileValue);
    }
}
