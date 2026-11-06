package it.jemulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FifoServerTest {
    @TempDir
    Path directory;

    @Test
    void processesCommandsBetweenDirectionalFifosAndStops() throws Exception {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        Path endpointBase = directory.resolve("device");
        Path input = directory.resolve("device.in");
        Path output = directory.resolve("device.out");
        CountDownLatch stopped = new CountDownLatch(1);
        LinkedBlockingQueue<String> logLines = new LinkedBlockingQueue<>();
        FifoServer server = new FifoServer(endpointBase,
                new ProtocolProcessor(new DeviceModel()), logLines::add,
                status -> {
                    if ("Fermato".equals(status)) {
                        stopped.countDown();
                    }
                });

        server.start();
        try (FileChannel commandChannel = FileChannel.open(input, WRITE);
             FileChannel responseChannel = FileChannel.open(output, READ);
             BufferedWriter commandWriter = new BufferedWriter(
                     new OutputStreamWriter(Channels.newOutputStream(commandChannel), StandardCharsets.UTF_8));
             BufferedReader responseReader = new BufferedReader(
                     new InputStreamReader(Channels.newInputStream(responseChannel), StandardCharsets.UTF_8))) {
            commandWriter.write("get an0");
            commandWriter.newLine();
            commandWriter.flush();
            assertTrue(responseReader.readLine().equals("2046"));
            assertTrue(logLines.poll(2, TimeUnit.SECONDS).endsWith("< 2046"));

            commandWriter.write("set out1 1");
            commandWriter.newLine();
            commandWriter.flush();
            assertTrue(responseReader.readLine().equals("1"));
            assertTrue(logLines.poll(2, TimeUnit.SECONDS).endsWith("< 1"));
        } finally {
            server.close();
        }

        assertTrue(stopped.await(2, TimeUnit.SECONDS));
    }
}
