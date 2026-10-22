package io.hyperfoil.tools.jjq.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end CLI tests (issue #182): the CLI entry calls
 * {@code System.exit}, so each case runs {@link JjqMain} in a child JVM
 * over piped stdin or temp files and asserts on stdout, stderr and the
 * exit code. Every run carries a timeout so a hanging parse (rather than
 * a clean error) fails the test instead of the build.
 */
class JjqCliTest {

    record Result(int exit, String stdout, String stderr) {}

    private static Result runCli(byte[] stdin, String... args) throws Exception {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        var cmd = new java.util.ArrayList<String>();
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(JjqMain.class.getName());
        cmd.addAll(List.of(args));
        var proc = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        try (OutputStream out = proc.getOutputStream()) {
            if (stdin != null) out.write(stdin);
        }
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        Thread stdoutDrain = drain(proc.getInputStream(), stdout);
        Thread stderrDrain = drain(proc.getErrorStream(), stderr);
        boolean finished = proc.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        stdoutDrain.join(5_000);
        stderrDrain.join(5_000);
        if (!finished) {
            proc.destroyForcibly();
            fail("CLI hung (no exit within 30s) for args " + List.of(args));
        }
        return new Result(proc.exitValue(),
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static Thread drain(InputStream in, ByteArrayOutputStream out) {
        Thread t = new Thread(() -> {
            try {
                in.transferTo(out);
            } catch (IOException ignored) {
                // Stream closed by teardown; buffered bytes are kept.
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static Result runStdin(String stdin, String... args) throws Exception {
        return runCli(stdin.getBytes(StandardCharsets.UTF_8), args);
    }

    @Test
    void jsonDefaultsUnchanged() throws Exception {
        Result r = runStdin("{\"a\":1}\n", ".a");
        assertEquals(0, r.exit());
        assertEquals("1\n", r.stdout());
    }

    @Test
    void yamlStdinToJson() throws Exception {
        Result r = runStdin("spec:\n  replicas: 2\n", "--input-format", "yaml", ".spec.replicas");
        assertEquals(0, r.exit());
        assertEquals("2\n", r.stdout());
    }

    @Test
    void jsonStdinToYaml() throws Exception {
        Result r = runStdin("{\"spec\":{\"replicas\":2}}\n", "--output-format", "yaml", ".");
        assertEquals(0, r.exit());
        assertEquals("spec:\n  replicas: 2\n", r.stdout());
    }

    @Test
    void yamlFileToYamlUpdate(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("deployment.yaml");
        Files.writeString(file, "spec:\n  replicas: 2\n");
        Result r = runCli(null, "--input-format", "yaml", "--output-format", "yaml",
                ".spec.replicas = 3", file.toString());
        assertEquals(0, r.exit());
        assertEquals("spec:\n  replicas: 3\n", r.stdout());
    }

    @Test
    void yamlMultiDocResultsSeparated() throws Exception {
        String input = "kind: A\n---\nkind: B\n";
        Result r = runStdin(input, "--input-format", "yaml", "--output-format", "yaml", ".");
        assertEquals(0, r.exit());
        assertEquals("kind: A\n---\nkind: B\n", r.stdout());
    }

    @Test
    void yamlSlurpCollectsDocuments() throws Exception {
        String input = "kind: A\n---\nkind: B\n";
        Result r = runStdin(input, "--input-format", "yaml", "-s", "-c", ".");
        assertEquals(0, r.exit());
        assertEquals("[{\"kind\":\"A\"},{\"kind\":\"B\"}]\n", r.stdout());
    }

    @Test
    void malformedInputFailsCleanly() throws Exception {
        // Issue #182: ":" used to loop parseAll until heap exhaustion.
        Result r = runStdin(":\n", ".");
        assertNotEquals(0, r.exit());
        assertTrue(r.stderr().contains("Invalid JSON value"),
                "stderr was: " + r.stderr());
    }

    @Test
    void unknownFormatRejected() throws Exception {
        Result r = runStdin("{\"a\":1}\n", "--input-format", "xml", ".");
        assertEquals(2, r.exit());
        assertTrue(r.stderr().contains("--input-format"));
    }

    @Test
    void colorsOptInOnly() throws Exception {
        // Piped output must never carry escapes, whatever the runtime
        // reports for System.console() (non-null with redirected IO on
        // JDK <=24). Only -C enables color.
        Result plain = runStdin("{\"a\":1}\n", ".a");
        assertFalse(plain.stdout().contains("\u001B"), "escapes leaked: " + plain.stdout());
        Result forced = runStdin("{\"a\":1}\n", "-C", ".a");
        assertTrue(forced.stdout().contains("\u001B["), "no escapes: " + forced.stdout());
        Result off = runStdin("{\"a\":1}\n", "-M", ".a");
        assertEquals("1\n", off.stdout());
    }
}
