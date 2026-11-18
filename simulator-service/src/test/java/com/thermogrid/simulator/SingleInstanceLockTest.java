package com.thermogrid.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * The simulator keeps its H2 file open for its whole life, and H2 refuses a second PROCESS on the same file. These
 * tests use a real second JVM, because H2 happily shares a file between threads of one JVM.
 */
class SingleInstanceLockTest {

    @TempDir
    Path dir;

    private record Result(int exit, String output) {
    }

    private Result probe(String url) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "com.thermogrid.simulator.H2OpenProbe", url).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).as("probe finished").isTrue();
        return new Result(p.exitValue(), out);
    }

    @Test
    void secondProcessOnTheSameDataFileIsRejectedWhileTheFirstHoldsIt() throws Exception {
        String url = "jdbc:h2:file:" + dir.resolve("simdb").toAbsolutePath();
        try (Connection holder = DriverManager.getConnection(url, "sa", "")) {
            assertThat(holder.isValid(2)).isTrue();
            Result r = probe(url);
            assertThat(r.exit()).as(r.output()).isEqualTo(3);
            assertThat(r.output()).contains("may be already in use");
        }
    }

    @Test
    void lockIsReleasedWhenTheFirstProcessCloses() throws Exception {
        String url = "jdbc:h2:file:" + dir.resolve("simdb").toAbsolutePath();
        try (Connection holder = DriverManager.getConnection(url, "sa", "")) {
            assertThat(probe(url).exit()).isEqualTo(3);
        }
        assertThat(probe(url).exit()).isZero();
    }

    @Test
    void differentDataDirectoriesDoNotBlockEachOther() throws Exception {
        File other = dir.resolve("other").toFile();
        assertThat(other.mkdirs()).isTrue();
        try (Connection holder = DriverManager.getConnection("jdbc:h2:file:" + dir.resolve("simdb").toAbsolutePath(),
                "sa", "")) {
            assertThat(probe("jdbc:h2:file:" + new File(other, "simdb").getAbsolutePath()).exit()).isZero();
        }
    }

    @Test
    void startupFailureIsExplainedInPlainLanguage() throws Exception {
        String url = "jdbc:h2:file:" + dir.resolve("simdb").toAbsolutePath();
        try (Connection holder = DriverManager.getConnection(url, "sa", "")) {
            Exception locked = null;
            // Same JVM shares the file, so build the exception text the way H2 reports it for another process.
            locked = new IllegalStateException("Failed to initialize pool",
                    new java.sql.SQLException("Database may be already in use: \"Locked by another process\""));
            FailureAnalysis analysis = new DuplicateInstanceFailureAnalyzer().analyze(locked);
            assertThat(analysis).isNotNull();
            assertThat(analysis.getDescription()).contains("Another simulator-service process")
                    .contains("one simulator may run per data directory");
            assertThat(analysis.getAction()).contains("Stop the other simulator-service");
        }
        assertThat(new DuplicateInstanceFailureAnalyzer().analyze(new IllegalStateException("unrelated"))).isNull();
    }
}
