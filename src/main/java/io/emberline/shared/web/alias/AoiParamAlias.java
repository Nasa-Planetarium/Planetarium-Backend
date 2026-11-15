package io.emberline.shared.web.alias;

import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.error.InvalidRequestException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Alias of a v1.2 layer whose AOI parameter the model team names {@code aoi} (api_contract.md) and {@code aoi_id}
 * (backend-quickstart.md): both names are accepted, {@code aoi} becomes {@code aoi_id}, then {@code target} rewrites
 * (same path, or the path variable). Both together → 400 invalid_param 40003 (MAPPING §8.1), written by the filter.
 */
public record AoiParamAlias(String aliasPath, HandoffAliasRule target) implements HandoffAliasRule {

    static final String AOI = "aoi";
    static final String AOI_ID = "aoi_id";

    @Override
    public Canonical rewrite(Map<String, String[]> params, String rawQuery) {
        if (params.containsKey(AOI) && params.containsKey(AOI_ID)) {
            throw new InvalidRequestException(ErrorCode.AOI_AND_BBOX, AOI, "give aoi or aoi_id, not both");
        }
        if (!params.containsKey(AOI)) {
            return target.rewrite(params, rawQuery);
        }
        Map<String, String[]> renamed = new LinkedHashMap<>();
        params.forEach((k, v) -> renamed.put(AOI.equals(k) ? AOI_ID : k, v));
        StringJoiner query = new StringJoiner("&");
        renamed.forEach((k, values) -> {
            for (String value : values) {
                query.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        });
        return target.rewrite(renamed, query.toString());
    }
}
