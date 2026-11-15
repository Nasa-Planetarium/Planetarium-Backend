package io.emberline.fireinfo.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves the SGG names of a fire event ({@code sgg_all}, comma-separated as stored) to aoi_list SGG AOIs (contract
 * {@code listFireEvents}, MAPPING §8.4): a name that only one SGG carries → that SGG (also across a SIDO border); a
 * name several SGGs carry (강서구·고성군·남구·동구·북구·서구·중구 in v1.2) → the one whose parent SIDO's name equals the
 * event's {@code sido_nm}; otherwise the name is dropped and counted. {@code sgg_cd} is not used.
 */
public final class SggResolver {

    /** An aoi_list SGG: its id, name and the name of its parent SIDO (null when the parent is unknown). */
    public record Sgg(String aoiId, String name, String sidoName) {
        public Sgg {
            Objects.requireNonNull(aoiId);
            Objects.requireNonNull(name);
        }
    }

    /** The SGG ids of one event (each once, in name order of sgg_all) and how many of its names were dropped. */
    public record Result(List<String> sggIds, int dropped) {
        public Result {
            sggIds = List.copyOf(sggIds);
        }
    }

    private final Map<String, List<Sgg>> byName = new LinkedHashMap<>();

    public SggResolver(List<Sgg> sggs) {
        for (Sgg s : sggs) {
            byName.computeIfAbsent(s.name(), k -> new ArrayList<>()).add(s);
        }
    }

    /** Names carried by more than one SGG. */
    public Set<String> homonyms() {
        Set<String> out = new LinkedHashSet<>();
        byName.forEach((name, list) -> {
            if (list.size() > 1) {
                out.add(name);
            }
        });
        return out;
    }

    public Result resolve(String sidoNm, String sggAll) {
        if (sggAll == null) {
            return new Result(List.of(), 0);
        }
        Set<String> names = new LinkedHashSet<>();
        for (String part : sggAll.split(",")) {
            String name = part.strip();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        Set<String> ids = new LinkedHashSet<>();
        int dropped = 0;
        for (String name : names) {
            List<Sgg> candidates = byName.getOrDefault(name, List.of());
            if (candidates.size() == 1) {
                ids.add(candidates.get(0).aoiId());
                continue;
            }
            List<Sgg> inSido = candidates.stream().filter(s -> sidoNm != null && sidoNm.equals(s.sidoName())).toList();
            if (inSido.size() == 1) {
                ids.add(inSido.get(0).aoiId());
            } else {
                dropped++;
            }
        }
        return new Result(new ArrayList<>(ids), dropped);
    }
}
