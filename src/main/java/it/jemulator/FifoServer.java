package it.jemulator;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.function.Consumer;

import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

public final class FifoServer implements AutoCloseable {
    private final Path fifoPath;
    private final ProtocolProcessor processor;
    private final Consumer<String> logger;
    private final Consumer<String> statusListener;

    private volatile boolean running;
    private FileChannel fifoChannel;
    private Thread worker;

    public FifoServer(
            Path fifoPath,
            ProtocolProcessor processor,
            Consumer<String> logger,
            Consumer<String> statusListener) {
        this.fifoPath = fifoPath;
        this.processor = processor;
        this.logger = logger;
        this.statusListener = statusListener;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }

        ensureFifo(fifoPath);
        fifoChannel = FileChannel.open(fifoPath, READ, WRITE);

        running = true;
        worker = new Thread(this::run, "fifo-protocol");
        worker.setDaemon(true);
        worker.start();
        statusListener.accept("In ascolto");
    }

    private void run() {
        try (BufferedReader reader = new BufferedReader(
                     new java.io.InputStreamReader(
                             java.nio.channels.Channels.newInputStream(fifoChannel),
                             StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                String response = processor.process(line);
                logger.accept("> " + line + "    < " + response);
            }
        } catch (IOException exception) {
            if (running) {
                statusListener.accept("Errore FIFO: " + exception.getMessage());
            }
        } finally {
            synchronized (this) {
                running = false;
                closeChannels();
            }
            statusListener.accept("Fermato");
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        closeChannels();
        if (worker == null || !worker.isAlive()) {
            statusListener.accept("Fermato");
        }
    }

    private void closeChannels() {
        try {
            if (fifoChannel == null) {
                return;
            }
            fifoChannel.close();
            fifoChannel = null;
        } catch (IOException exception) {
            statusListener.accept("Errore chiusura FIFO: " + exception.getMessage());
        }
    }

    private static void ensureFifo(Path path) throws IOException {
        Path absolutePath = path.toAbsolutePath().normalize();
        if (Files.exists(absolutePath)) {
            Object mode = Files.getAttribute(absolutePath, "unix:mode");
            if (!(mode instanceof Integer permissions) || (permissions & 0170000) != 0010000) {
                throw new IOException("Il percorso esiste ma non e' una FIFO: " + absolutePath);
            }
            return;
        }

        Path parent = absolutePath.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("La directory della FIFO non esiste: " + parent);
        }

        Process process = new ProcessBuilder("mkfifo", "--", absolutePath.toString())
                .redirectErrorStream(true)
                .start();
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String error = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                throw new IOException("Impossibile creare la FIFO " + absolutePath
                        + (error.isEmpty() ? "" : ": " + error));
            }
            Files.setPosixFilePermissions(absolutePath, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Creazione FIFO interrotta: " + absolutePath, exception);
        }
    }
}
