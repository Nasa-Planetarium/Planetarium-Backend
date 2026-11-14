package io.emberline.shared.cli;

/** Result of a one-shot profile (ingest): the process exit code and the JSON line printed last on stdout. */
public interface CliOutcome {

    int exitCode();

    String lastLine();
}
