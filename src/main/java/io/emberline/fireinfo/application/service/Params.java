package io.emberline.fireinfo.application.service;

import io.emberline.fireinfo.domain.Bbox;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/** Parameter rules shared by the fire-info routes (400 invalid_param with the parameter's name). */
final class Params {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("uuuu-MM").withResolverStyle(ResolverStyle.STRICT);

    private Params() {
    }

    static boolean given(String value) {
        return value != null && !value.isEmpty();
    }

    static LocalDate date(String text, String param) {
        try {
            return LocalDate.parse(text, DATE);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, param, param + " must be a calendar date YYYY-MM-DD");
        }
    }

    static LocalDate requiredDate(String text, String param) {
        if (!given(text)) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, param, param + " is required");
        }
        return date(text, param);
    }

    static YearMonth yearMonth(String text, String param) {
        try {
            return YearMonth.parse(text, YEAR_MONTH);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, param, param + " must be YYYY-MM");
        }
    }

    static Bbox bbox(String text) {
        return Bbox.parse(text).orElseThrow(() -> new InvalidRequestException(ErrorCode.INVALID_PARAM, "bbox",
                "bbox must be west,south,east,north with west < east, south < north, longitudes within ±180 and latitudes within ±90"));
    }

    /** {@code aoi_id} and {@code bbox} together → 400 40003; neither → 400 invalid_param. */
    static void exactlyOneArea(boolean hasAoi, boolean hasBbox) {
        if (hasAoi && hasBbox) {
            throw new InvalidRequestException(ErrorCode.AOI_AND_BBOX, "bbox", "give aoi_id or bbox, not both");
        }
        if (!hasAoi && !hasBbox) {
            throw new InvalidRequestException(ErrorCode.INVALID_PARAM, "aoi_id", "give exactly one of aoi_id or bbox");
        }
    }

    /** bbox area over {@code emberline.limits.layer-bbox-area-deg2} → 400 range_too_large 40105. */
    static void bboxArea(Bbox box, double maxDeg2) {
        double deg2 = box.areaDeg2();
        if (deg2 > maxDeg2) {
            throw new InvalidRequestException(ErrorCode.BBOX_AREA_TOO_LARGE, "bbox", "bbox area is limited to "
                    + plain(maxDeg2) + " square degrees (requested " + plain(Math.round(deg2 * 100) / 100.0) + ")");
        }
    }

    static String plain(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    /** {@code key=value} pairs joined with {@code &}, null as empty — the normalized query of a cache key. */
    static String normalized(Object... keyValues) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(keyValues[i]).append('=').append(keyValues[i + 1] == null ? "" : keyValues[i + 1]);
        }
        return sb.toString();
    }
}
