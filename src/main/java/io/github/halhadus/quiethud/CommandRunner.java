package io.github.halhadus.quiethud;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class CommandRunner {
    private CommandRunner() {}
    static String run(String command) throws Exception {
        Process process = new ProcessBuilder("/bin/sh", "-c", command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        Set<ProcessHandle> children = new HashSet<>();
        AtomicReference<String> text = new AtomicReference<>();
        AtomicReference<IOException> readError = new AtomicReference<>();
        Thread reader = Thread.ofVirtual().name("QuietHUD command output").start(() -> {
            try (var input = process.getInputStream()) {
                byte[] output = input.readNBytes(4097);
                if (output.length > 4096) { readError.set(new IOException("Command output exceeds 4096 bytes")); process.destroyForcibly(); }
                else text.set(new String(output, StandardCharsets.UTF_8));
            } catch (IOException ex) { readError.compareAndSet(null, ex); }
        });
        try {
            long deadline = System.nanoTime() + 1500000000L;
            do {
                process.descendants().forEach(children::add);
                if (process.waitFor(20, TimeUnit.MILLISECONDS)) break;
                if (System.nanoTime() >= deadline) throw new IOException("Command exceeded 1500 ms");
            } while (true);
            reader.join(200);
            if (readError.get() != null) throw readError.get();
            if (process.exitValue() != 0) throw new IOException("Command exit code " + process.exitValue());
            if (text.get() == null) throw new IOException("Command output pipe did not close; background jobs are unsupported");
            return Sampler.clean(text.get());
        } finally {
            process.descendants().forEach(children::add);
            children.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            process.destroyForcibly(); reader.interrupt();
            try { process.getInputStream().close(); process.getOutputStream().close(); }
            catch (IOException ex) { /* The process may already have closed its pipes. */ }
        }
    }
}
