import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Provider-free JVM fixture whose shutdown hook reads the snapshotted classpath. */
public final class SnapshotProbe {
    /** @param args probe output directory, mode, and optional application arguments */
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        String classpath = System.getProperty("java.class.path");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.writeString(output.resolve("settling"), "started");
                Thread.sleep(250);
                StringBuilder payload = new StringBuilder();
                for (String entry : classpath.split(java.io.File.pathSeparator)) {
                    Path jar = Path.of(entry);
                    if (!Files.isRegularFile(jar)) {
                        throw new IllegalStateException("Missing classpath during shutdown: " + jar);
                    }
                    if (jar.getFileName().toString().equals("payload.jar")) {
                        payload.append(Files.readString(jar));
                    }
                }
                Files.writeString(output.resolve("settled"), payload);
            } catch (Exception error) {
                try {
                    Files.writeString(output.resolve("failure"), error.toString());
                } catch (Exception ignored) {
                    error.printStackTrace();
                }
            }
        }));
        Files.writeString(output.resolve("pid"), Long.toString(ProcessHandle.current().pid()));
        Files.writeString(output.resolve("args"), String.join("\n", Arrays.copyOfRange(args, 2, args.length)));
        Files.writeString(output.resolve("classpath"), classpath);
        Files.writeString(output.resolve("jvm-temp"), System.getProperty("java.io.tmpdir"));
        Files.writeString(output.resolve("ready"), "ready");
        if (args[1].equals("stdin")) {
            Files.writeString(output.resolve("stdin"), new String(System.in.readAllBytes()));
        } else if (args[1].equals("wait")) {
            while (!Files.exists(output.resolve("release"))) {
                Thread.sleep(25);
            }
        } else {
            System.exit(Integer.parseInt(args[1]));
        }
    }
}
