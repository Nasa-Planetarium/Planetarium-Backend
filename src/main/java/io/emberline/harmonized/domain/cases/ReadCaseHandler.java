package io.emberline.harmonized.domain.cases;

import io.emberline.harmonized.domain.Reading;

/** What the read side does with a reading that falls into one data case. */
public interface ReadCaseHandler {

    ReadCase handles();

    boolean matches(Reading reading);

    Reading apply(Reading reading);
}
