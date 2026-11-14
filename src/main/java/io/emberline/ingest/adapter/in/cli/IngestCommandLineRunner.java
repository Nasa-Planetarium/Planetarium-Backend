package io.emberline.ingest.adapter.in.cli;

import io.emberline.ingest.application.port.in.ActivateReleaseUseCase;
import io.emberline.ingest.application.port.in.IngestOutcome;
import io.emberline.ingest.application.port.in.ShowReleaseUseCase;
import io.emberline.ingest.application.port.in.StageReleaseUseCase;
import io.emberline.shared.cli.CliOutcome;
import io.emberline.shared.error.EmberlineException;
import io.emberline.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Profile {@code ingest} (web server off): {@code stage <key>}, {@code activate <key> --expect-sha256 <hex>},
 * {@code show <key>}. Exit 0 ok, 3 RELEASE_INVALID / RELEASE_KEY_INVALID, 4 RELEASE_HASH_MISMATCH,
 * 5 RELEASE_STATE_CONFLICT. The last stdout line is {@code {release_key, state, reason_codes, aliases_used, ...}}
 * (printed by main after the context closed, so no log line follows it).
 */
@Component
@Profile("ingest")
class IngestCommandLineRunner implements ApplicationRunner, ExitCodeGenerator, CliOutcome {

    private static final Logger log = LoggerFactory.getLogger(IngestCommandLineRunner.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Map<String, Function<Command, IngestOutcome>> commands = new LinkedHashMap<>();
    private int exitCode = 3;
    private String lastLine = "{}";

    IngestCommandLineRunner(StageReleaseUseCase stage, ActivateReleaseUseCase activate, ShowReleaseUseCase show) {
        commands.put("stage", c -> stage.stage(c.key()));
        commands.put("activate", c -> activate.activate(c.key(), c.expectSha256()));
        commands.put("show", c -> show.show(c.key()));
    }

    /** Parsed command line: {@code <command> <release_key> [--expect-sha256 <hex>|--expect-sha256=<hex>]}. */
    record Command(String name, String key, String expectSha256) {
        static Command parse(String[] args) {
            List<String> positional = new ArrayList<>();
            String expect = null;
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.equals("--expect-sha256") && i + 1 < args.length) {
                    expect = args[++i];
                } else if (a.startsWith("--expect-sha256=")) {
                    expect = a.substring("--expect-sha256=".length());
                } else if (!a.startsWith("--")) {
                    positional.add(a);
                }
            }
            return new Command(positional.isEmpty() ? null : positional.get(0),
                    positional.size() > 1 ? positional.get(1) : null, expect);
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        Command command = Command.parse(args.getSourceArgs());
        IngestOutcome outcome;
        try {
            Function<Command, IngestOutcome> handler = command.name() == null ? null : commands.get(command.name());
            outcome = handler == null
                    ? usage(command)
                    : handler.apply(command);
        } catch (EmberlineException e) {
            ErrorCode code = e.errorCode();
            outcome = new IngestOutcome(safeKey(command.key()), "UNCHANGED", List.of(code.name()), List.of(), List.of(),
                    null, null, Map.of(), code.cliExit().orElse(3));
        } catch (RuntimeException e) {
            log.error("ingest command failed", e);
            outcome = new IngestOutcome(safeKey(command.key()), "UNCHANGED", List.of(ErrorCode.INTERNAL.name()),
                    List.of(), List.of(), null, null, Map.of(), 1);
        }
        exitCode = outcome.exitCode();
        lastLine = toJson(outcome);
        log.info("ingest {} {} -> exit {} {}", command.name(), safeKey(command.key()), exitCode, lastLine);
    }

    private static IngestOutcome usage(Command c) {
        return new IngestOutcome(safeKey(c.key()), "UNCHANGED", List.of("USAGE:stage|activate|show <release_key>"),
                List.of(), List.of(), null, null, Map.of(), ErrorCode.RELEASE_INVALID.cliExit().orElse(3));
    }

    /** Never echo an arbitrary key back verbatim (control characters, huge strings). */
    private static String safeKey(String key) {
        if (key == null) {
            return null;
        }
        String cleaned = key.replaceAll("[^A-Za-z0-9._/-]", "?");
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    private static String toJson(IngestOutcome o) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("release_key", o.releaseKey());
        out.put("state", o.state());
        out.put("reason_codes", o.reasonCodes());
        out.put("aliases_used", o.aliasesUsed());
        out.put("warnings", o.warnings());
        out.put("method_version", o.methodVersion());
        out.put("data_version", o.dataVersion());
        out.put("rows", o.rows());
        out.put("exit_code", o.exitCode());
        return JSON.writeValueAsString(out);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    @Override
    public int exitCode() {
        return exitCode;
    }

    @Override
    public String lastLine() {
        return lastLine;
    }
}
