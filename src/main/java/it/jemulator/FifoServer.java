package it.jemulator;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

public final class FifoServer implements AutoCloseable {
    private static final int BUFFER_SIZE = 4_096;
    private static final int ERROR_BROKEN_PIPE = 109;
    private static final int ERROR_PIPE_CONNECTED = 535;

    private final Path endpointBase;
    private final ProtocolProcessor processor;
    private final Consumer<String> logger;
    private final Consumer<String> statusListener;

    private volatile boolean running;
    private FileChannel inputChannel;
    private FileChannel outputChannel;
    private WinNT.HANDLE inputPipe;
    private WinNT.HANDLE outputPipe;
    private Thread worker;

    public FifoServer(
            Path endpointBase,
            ProtocolProcessor processor,
            Consumer<String> logger,
            Consumer<String> statusListener) {
        this.endpointBase = endpointBase;
        this.processor = processor;
        this.logger = logger;
        this.statusListener = statusListener;
    }

    public static Path defaultEndpointBase() {
        return Path.of(isWindows() ? "jemulator" : "/tmp/jemulator");
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }

        if (isWindows()) {
            createWindowsPipes();
        } else {
            Path inputPath = inputPath();
            Path outputPath = outputPath();
            ensureFifo(inputPath);
            ensureFifo(outputPath);
            inputChannel = FileChannel.open(inputPath, READ, WRITE);
            outputChannel = FileChannel.open(outputPath, READ, WRITE);
        }

        running = true;
        worker = new Thread(this::run, "fifo-protocol");
        worker.setDaemon(true);
        worker.start();
        statusListener.accept("In ascolto: " + endpoints());
    }

    public String endpoints() {
        if (isWindows()) {
            return windowsPipeName("in") + " (comandi), " + windowsPipeName("out") + " (risposte)";
        }
        return inputPath() + " (comandi), " + outputPath() + " (risposte)";
    }

    private void run() {
        try {
            if (isWindows()) {
                waitForWindowsClients();
                if (!running) {
                    return;
                }
            }
            try (BufferedReader reader = new BufferedReader(
                         new java.io.InputStreamReader(inputStream(), StandardCharsets.UTF_8));
                 PrintWriter writer = new PrintWriter(outputStream(), true, StandardCharsets.UTF_8)) {
                String line;
                while (running && (line = reader.readLine()) != null) {
                    String response = processor.process(line);
                    logger.accept("> " + line + "    < " + response);
                    writer.println(response);
                }
            }
        } catch (IOException exception) {
            if (running) {
                statusListener.accept("Errore endpoint: " + exception.getMessage());
            }
        } finally {
            synchronized (this) {
                running = false;
                closeChannels();
            }
            statusListener.accept("Fermato");
        }
    }

    private InputStream inputStream() {
        if (isWindows()) {
            return new NamedPipeInputStream(inputPipe);
        }
        return Channels.newInputStream(inputChannel);
    }

    private OutputStream outputStream() {
        if (isWindows()) {
            return new NamedPipeOutputStream(outputPipe);
        }
        return Channels.newOutputStream(outputChannel);
    }

    private void createWindowsPipes() throws IOException {
        inputPipe = createNamedPipe(windowsPipeName("in"), WinBase.PIPE_ACCESS_INBOUND);
        try {
            outputPipe = createNamedPipe(windowsPipeName("out"), WinBase.PIPE_ACCESS_OUTBOUND);
        } catch (IOException exception) {
            closeChannels();
            throw exception;
        }
    }

    private WinNT.HANDLE createNamedPipe(String name, int direction) throws IOException {
        WinNT.HANDLE pipe = Kernel32.INSTANCE.CreateNamedPipe(
                name,
                direction,
                WinBase.PIPE_TYPE_BYTE | WinBase.PIPE_READMODE_BYTE | WinBase.PIPE_WAIT,
                1,
                BUFFER_SIZE,
                BUFFER_SIZE,
                0,
                null);
        if (pipe == null || WinBase.INVALID_HANDLE_VALUE.equals(pipe)) {
            throw windowsError("Impossibile creare la named pipe " + name);
        }
        return pipe;
    }

    private void waitForWindowsClients() throws IOException {
        CountDownLatch connected = new CountDownLatch(2);
        AtomicReference<IOException> failure = new AtomicReference<>();
        waitForWindowsClient(inputPipe, connected, failure, "named-pipe-input");
        waitForWindowsClient(outputPipe, connected, failure, "named-pipe-output");
        try {
            connected.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Connessione named pipe interrotta", exception);
        }
        IOException exception = failure.get();
        if (exception != null) {
            throw exception;
        }
    }

    private void waitForWindowsClient(
            WinNT.HANDLE pipe,
            CountDownLatch connected,
            AtomicReference<IOException> failure,
            String threadName) {
        Thread thread = new Thread(() -> {
            try {
                if (!Kernel32.INSTANCE.ConnectNamedPipe(pipe, null)
                        && Kernel32.INSTANCE.GetLastError() != ERROR_PIPE_CONNECTED
                        && running) {
                    failure.compareAndSet(null, windowsError("Impossibile connettere la named pipe"));
                }
            } finally {
                connected.countDown();
            }
        }, threadName);
        thread.setDaemon(true);
        thread.start();
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
        closeChannel(inputChannel);
        inputChannel = null;
        closeChannel(outputChannel);
        outputChannel = null;
        closePipe(inputPipe);
        inputPipe = null;
        closePipe(outputPipe);
        outputPipe = null;
    }

    private void closeChannel(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException exception) {
            statusListener.accept("Errore chiusura endpoint: " + exception.getMessage());
        }
    }

    private void closePipe(WinNT.HANDLE pipe) {
        if (pipe == null || WinBase.INVALID_HANDLE_VALUE.equals(pipe)) {
            return;
        }
        Kernel32.INSTANCE.DisconnectNamedPipe(pipe);
        Kernel32.INSTANCE.CloseHandle(pipe);
    }

    private Path inputPath() {
        return endpointPath("in");
    }

    private Path outputPath() {
        return endpointPath("out");
    }

    private Path endpointPath(String direction) {
        Path absoluteBase = endpointBase.toAbsolutePath().normalize();
        Path fileName = absoluteBase.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("Il percorso base deve includere un nome: " + endpointBase);
        }
        return absoluteBase.resolveSibling(fileName + "." + direction);
    }

    private String windowsPipeName(String direction) {
        Path fileName = endpointBase.getFileName();
        if (fileName == null || fileName.toString().isBlank()) {
            throw new IllegalArgumentException("Il nome della named pipe non e' valido: " + endpointBase);
        }
        return "\\\\.\\pipe\\" + fileName + "." + direction;
    }

    private static IOException windowsError(String message) {
        return new IOException(message + " (errore Windows " + Kernel32.INSTANCE.GetLastError() + ")");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
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

    private static final class NamedPipeInputStream extends InputStream {
        private final WinNT.HANDLE pipe;

        private NamedPipeInputStream(WinNT.HANDLE pipe) {
            this.pipe = pipe;
        }

        @Override
        public int read() throws IOException {
            byte[] byteValue = new byte[1];
            return read(byteValue, 0, 1) == -1 ? -1 : Byte.toUnsignedInt(byteValue[0]);
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            byte[] buffer = offset == 0 && length == bytes.length ? bytes : new byte[length];
            IntByReference bytesRead = new IntByReference();
            if (!Kernel32.INSTANCE.ReadFile(pipe, buffer, length, bytesRead, null)) {
                if (Kernel32.INSTANCE.GetLastError() == ERROR_BROKEN_PIPE) {
                    return -1;
                }
                throw windowsError("Errore lettura named pipe");
            }
            int count = bytesRead.getValue();
            if (buffer != bytes) {
                System.arraycopy(buffer, 0, bytes, offset, count);
            }
            return count;
        }
    }

    private static final class NamedPipeOutputStream extends OutputStream {
        private final WinNT.HANDLE pipe;

        private NamedPipeOutputStream(WinNT.HANDLE pipe) {
            this.pipe = pipe;
        }

        @Override
        public void write(int byteValue) throws IOException {
            write(new byte[] {(byte) byteValue}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            int written = 0;
            while (written < length) {
                int remaining = length - written;
                byte[] buffer = new byte[remaining];
                System.arraycopy(bytes, offset + written, buffer, 0, remaining);
                IntByReference bytesWritten = new IntByReference();
                if (!Kernel32.INSTANCE.WriteFile(pipe, buffer, remaining, bytesWritten, null)) {
                    throw windowsError("Errore scrittura named pipe");
                }
                int count = bytesWritten.getValue();
                if (count == 0) {
                    throw new IOException("Scrittura named pipe completata senza trasferire dati");
                }
                written += count;
            }
        }
    }
}
