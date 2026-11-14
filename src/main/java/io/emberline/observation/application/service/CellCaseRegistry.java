package io.emberline.observation.application.service;

import io.emberline.observation.application.port.in.CellCollection.CellProperties;
import io.emberline.observation.domain.CellCase;
import io.emberline.observation.domain.CellRow;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Case → handler map; a row goes to the first case (declaration order of {@link CellCase}) whose handler applies. */
@Component
class CellCaseRegistry {

    private final List<CellCaseHandler> ordered;

    CellCaseRegistry(List<CellCaseHandler> handlers) {
        Map<CellCase, CellCaseHandler> byCase = new EnumMap<>(CellCase.class);
        handlers.forEach(h -> byCase.put(h.handles(), h));
        if (byCase.size() != CellCase.values().length) {
            throw new IllegalStateException("every CellCase needs exactly one handler");
        }
        this.ordered = Arrays.stream(CellCase.values()).map(byCase::get).toList();
    }

    CellProperties properties(CellRow row) {
        for (CellCaseHandler h : ordered) {
            if (h.applies(row)) {
                return h.properties(row);
            }
        }
        throw new IllegalStateException("no cell case applies");
    }
}
