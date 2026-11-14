package io.emberline.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 00-decisions E4: data cases are handled by one handler class per case plus a registry map — no {@code switch}
 * statements or switch expressions anywhere in src/main.
 */
class SourceRulesTest {

    static final Pattern SWITCH = Pattern.compile("\\bswitch\\s*\\(");
    static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");
    static final Pattern STRINGS = Pattern.compile("(?s)\"\"\".*?\"\"\"|\"(\\\\.|[^\"\\\\\\n])*\"");

    @Test
    void noSwitchInMain() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = STRINGS.matcher(COMMENTS.matcher(Files.readString(f)).replaceAll(" ")).replaceAll("\"\"");
                if (SWITCH.matcher(code).find()) {
                    violations.add(f.toString());
                }
            }
        }
        assertThat(violations).as("switch statements/expressions (E4: use handler classes + a registry)").isEmpty();
    }
}
