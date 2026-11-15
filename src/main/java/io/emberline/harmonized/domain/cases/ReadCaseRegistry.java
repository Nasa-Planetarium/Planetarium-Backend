package io.emberline.harmonized.domain.cases;

import io.emberline.harmonized.domain.Reading;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Case → handler (one class per case and a map, no switch). {@link #apply} runs every matching case in order. */
public final class ReadCaseRegistry {

    private static final Map<ReadCase, ReadCaseHandler> HANDLERS = new EnumMap<>(ReadCase.class);

    static {
        for (ReadCaseHandler h : List.of(new MissingMonthHandler(), new PartialMonthHandler(),
                new IntervalOutOfRangeHandler(), new IntervalUnavailableHandler(), new LowSampleHandler())) {
            HANDLERS.put(h.handles(), h);
        }
    }

    private ReadCaseRegistry() {
    }

    public static ReadCaseHandler handler(ReadCase readCase) {
        return HANDLERS.get(readCase);
    }

    public static Reading apply(Reading reading) {
        Reading out = reading;
        for (ReadCaseHandler h : HANDLERS.values()) {
            if (h.matches(out)) {
                out = h.apply(out);
            }
        }
        return out;
    }
}
