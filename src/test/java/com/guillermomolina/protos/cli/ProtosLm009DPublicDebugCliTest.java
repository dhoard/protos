/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
 * DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION OF
 * DERIVATIVE WORKS BASED ON, THE LICENSED WORK CONSTITUTES RECIPIENT'S ACCEPTANCE
 * OF THIS LICENSE AND ITS TERMS, WHETHER OR NOT SUCH RECIPIENT READS THE TERMS OF
 * THE LICENSE. "LICENSED WORK" AND "RECIPIENT" ARE DEFINED IN THE LICENSE. A COPY
 * OF THE LICENSE IS LOCATED IN THE TEXT FILE ENTITLED "LICENSE.TXT" ACCOMPANYING
 * THE CONTENTS OF THIS FILE. IF A COPY OF THE LICENSE DOES NOT ACCOMPANY THIS
 * FILE, A COPY OF THE LICENSE MAY ALSO BE OBTAINED AT THE FOLLOWING WEB SITE:
 * https://github.com/guillermomolina/protos
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 */
package com.guillermomolina.protos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class ProtosLm009DPublicDebugCliTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern READINESS =
            Pattern.compile(
                    "^PROTOS_DEBUG_READY "
                            + "\\{\\\"version\\\":1,"
                            + "\\\"protocol\\\":\\\"dap\\\","
                            + "\\\"transport\\\":\\\"tcp\\\","
                            + "\\\"host\\\":\\\"([^\\\"]+)\\\","
                            + "\\\"port\\\":([0-9]+)\\}$");

    @Test
    void publicDebugCommandPublishesReadinessAndRoutesGuestStreamsThroughRealDap()
            throws Exception {
        Path root = Files.createTempDirectory("protos-lm009-d2-");
        Path source = root.resolve("main.protos");
        Path helper = root.resolve("helper.protos");
        ByteArrayOutputStream controlOut = new ByteArrayOutputStream();
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);

        Files.writeString(
                helper,
                "value: \"application-out\"\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                source,
                "Helper: import(\"./helper.protos\")\n"
                        + "errorWriter: TextWriter(process.stderr(), process.stderrEncoding())\n"
                        + "print(Helper.value)\n"
                        + "errorWriter.writeLine(process.args().at(1)).value()",
                StandardCharsets.UTF_8);

        Thread cliThread =
                Thread.ofPlatform()
                        .name("protos-lm009-d2-cli")
                        .start(
                                () ->
                                        exitCode.set(
                                                new ProtosCli()
                                                        .run(
                                                                new String[] {
                                                                    "debug",
                                                                    source.toString(),
                                                                    "application-out",
                                                                    "application-err"
                                                                },
                                                                InputStream.nullInputStream(),
                                                                new PrintStream(
                                                                        controlOut,
                                                                        true,
                                                                        StandardCharsets.UTF_8),
                                                                new PrintStream(
                                                                        diagnostics,
                                                                        true,
                                                                        StandardCharsets.UTF_8))));

        DapClient client = null;
        try {
            String readiness = awaitReadinessLine(controlOut);
            Matcher match = READINESS.matcher(readiness);
            assertTrue(match.matches(), () -> "Unexpected readiness: " + readiness);

            String host = match.group(1);
            int port = Integer.parseInt(match.group(2));
            assertTrue(InetAddress.getByName(host).isLoopbackAddress());
            assertTrue(port > 0 && port <= 65535);

            client = DapClient.connect(host, port, TIMEOUT);

            int initialize =
                    client.request(
                            "initialize",
                            "{\"adapterID\":\"protos\","
                                    + "\"clientID\":\"protos-lm009-d2\","
                                    + "\"clientName\":\"Protos LM009-D2\","
                                    + "\"linesStartAt1\":true,"
                                    + "\"columnsStartAt1\":true,"
                                    + "\"pathFormat\":\"path\"}");
            assertSuccessfulResponse(
                    client.awaitResponse(initialize, TIMEOUT),
                    initialize,
                    "initialize");
            client.awaitEvent("initialized", TIMEOUT);

            int launch = client.request("launch", "{}");
            assertSuccessfulResponse(
                    client.awaitResponse(launch, TIMEOUT),
                    launch,
                    "launch");

            int configurationDone = client.request("configurationDone", "{}");
            assertSuccessfulResponse(
                    client.awaitResponse(configurationDone, TIMEOUT),
                    configurationDone,
                    "configurationDone");

            String stdoutEvent =
                    client.awaitOutputContaining(
                            "stdout", "application-out", TIMEOUT);
            assertTrue(stdoutEvent.contains("application-out"), stdoutEvent);

            String stderrEvent =
                    client.awaitOutputContaining(
                            "stderr", "application-err", TIMEOUT);
            assertTrue(stderrEvent.contains("application-err"), stderrEvent);

            String terminated = client.awaitEvent("terminated", TIMEOUT);
            assertTrue(
                    terminated.contains("\"event\":\"terminated\""),
                    () -> "Expected DAP terminated event: " + terminated);

            client.close();
            client = null;

            cliThread.join(TIMEOUT.toMillis());
            assertFalse(
                    cliThread.isAlive(),
                    "public protos debug invocation must complete after DAP transport teardown");
            assertEquals(0, exitCode.get(),
                    () -> "Public debug CLI exited nonzero; diagnostics:\n"
                            + diagnostics.toString(StandardCharsets.UTF_8));

            String controlText = controlOut.toString(StandardCharsets.UTF_8);
            assertEquals(readiness + System.lineSeparator(), controlText);
            assertFalse(controlText.contains("application-out"), controlText);
            assertFalse(controlText.contains("application-err"), controlText);

            String diagnosticText = diagnostics.toString(StandardCharsets.UTF_8);
            assertFalse(
                    diagnosticText.contains(
                            "[Graal DAP] Starting server and listening on"),
                    "raw Graal readiness must not escape the version-bounded adapter");
            assertFalse(diagnosticText.contains("application-out"), diagnosticText);
            assertFalse(diagnosticText.contains("application-err"), diagnosticText);
        } finally {
            if (client != null) {
                client.close();
            }
            if (cliThread.isAlive()) {
                cliThread.interrupt();
                cliThread.join(TIMEOUT.toMillis());
            }
            Files.deleteIfExists(helper);
            Files.deleteIfExists(source);
            Files.deleteIfExists(root);
        }
    }

    private static String awaitReadinessLine(ByteArrayOutputStream output)
            throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            String current = output.toString(StandardCharsets.UTF_8);
            int newline = current.indexOf('\n');
            if (newline >= 0) {
                String line = current.substring(0, newline);
                return line.endsWith("\r")
                        ? line.substring(0, line.length() - 1)
                        : line;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError(
                "Timed out waiting for PROTOS_DEBUG_READY; stdout="
                        + output.toString(StandardCharsets.UTF_8));
    }

    private static void assertSuccessfulResponse(
            String message, int requestSequence, String command) {
        assertEquals("response", stringField(message, "type"));
        assertEquals(requestSequence, integerField(message, "request_seq"));
        assertEquals(command, stringField(message, "command"));
        assertTrue(
                booleanField(message, "success"),
                () -> "DAP " + command + " failed: " + message);
    }

    private static String stringField(String json, String name) {
        Pattern pattern =
                Pattern.compile(
                        "\\\""
                                + Pattern.quote(name)
                                + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
        Matcher matcher = pattern.matcher(json);
        assertTrue(matcher.find(), () -> "Missing JSON string field " + name + ": " + json);
        return matcher.group(1);
    }

    private static int integerField(String json, String name) {
        Pattern pattern =
                Pattern.compile(
                        "\\\""
                                + Pattern.quote(name)
                                + "\\\"\\s*:\\s*(-?[0-9]+)");
        Matcher matcher = pattern.matcher(json);
        assertTrue(matcher.find(), () -> "Missing JSON integer field " + name + ": " + json);
        return Integer.parseInt(matcher.group(1));
    }

    private static boolean booleanField(String json, String name) {
        Pattern pattern =
                Pattern.compile(
                        "\\\""
                                + Pattern.quote(name)
                                + "\\\"\\s*:\\s*(true|false)");
        Matcher matcher = pattern.matcher(json);
        assertTrue(matcher.find(), () -> "Missing JSON boolean field " + name + ": " + json);
        return Boolean.parseBoolean(matcher.group(1));
    }

    private static final class DapClient implements AutoCloseable {
        private static final Pattern RESPONSE_REQUEST =
                Pattern.compile("\\\"request_seq\\\"\\s*:\\s*([0-9]+)");

        private final Socket socket;
        private final BufferedInputStream input;
        private final BufferedOutputStream output;
        private final Deque<String> deferred = new ArrayDeque<>();
        private int nextSequence = 1;

        private DapClient(Socket socket) throws IOException {
            this.socket = socket;
            this.input = new BufferedInputStream(socket.getInputStream());
            this.output = new BufferedOutputStream(socket.getOutputStream());
        }

        static DapClient connect(
                String host, int port, Duration timeout)
                throws IOException {
            Socket socket = new Socket();
            socket.connect(
                    new InetSocketAddress(host, port),
                    (int) timeout.toMillis());
            socket.setSoTimeout((int) timeout.toMillis());
            return new DapClient(socket);
        }

        int request(String command, String argumentsJson) throws IOException {
            int sequence = nextSequence++;
            String body =
                    "{"
                            + "\"seq\":" + sequence + ","
                            + "\"type\":\"request\","
                            + "\"command\":\"" + command + "\","
                            + "\"arguments\":" + argumentsJson
                            + "}";
            write(body);
            return sequence;
        }

        String awaitResponse(int requestSequence, Duration timeout)
                throws IOException {
            String existing = removeDeferredResponse(requestSequence);
            if (existing != null) {
                return existing;
            }

            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                String message = read();
                if ("response".equals(optionalStringField(message, "type"))
                        && responseRequestSequence(message) == requestSequence) {
                    return message;
                }
                deferred.addLast(message);
            }
            throw new IOException(
                    "Timed out waiting for DAP response request_seq="
                            + requestSequence);
        }

        String awaitEvent(String eventName, Duration timeout)
                throws IOException {
            String existing = removeDeferredEvent(eventName);
            if (existing != null) {
                return existing;
            }

            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                String message = read();
                if ("event".equals(optionalStringField(message, "type"))
                        && eventName.equals(optionalStringField(message, "event"))) {
                    return message;
                }
                deferred.addLast(message);
            }
            throw new IOException("Timed out waiting for DAP event " + eventName);
        }

        String awaitOutputContaining(
                String category, String fragment, Duration timeout)
                throws IOException {
            String existing =
                    removeDeferredOutputContaining(category, fragment);
            if (existing != null) {
                return existing;
            }

            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                String message = read();
                if ("event".equals(optionalStringField(message, "type"))
                        && "output".equals(optionalStringField(message, "event"))
                        && category.equals(optionalStringField(message, "category"))
                        && message.contains(fragment)) {
                    return message;
                }
                deferred.addLast(message);
            }
            throw new IOException(
                    "Timed out waiting for DAP "
                            + category
                            + " output containing "
                            + fragment);
        }

        private String removeDeferredResponse(int requestSequence) {
            Iterator<String> iterator = deferred.iterator();
            while (iterator.hasNext()) {
                String message = iterator.next();
                if ("response".equals(optionalStringField(message, "type"))
                        && responseRequestSequence(message) == requestSequence) {
                    iterator.remove();
                    return message;
                }
            }
            return null;
        }

        private String removeDeferredEvent(String eventName) {
            Iterator<String> iterator = deferred.iterator();
            while (iterator.hasNext()) {
                String message = iterator.next();
                if ("event".equals(optionalStringField(message, "type"))
                        && eventName.equals(optionalStringField(message, "event"))) {
                    iterator.remove();
                    return message;
                }
            }
            return null;
        }

        private String removeDeferredOutputContaining(
                String category, String fragment) {
            Iterator<String> iterator = deferred.iterator();
            while (iterator.hasNext()) {
                String message = iterator.next();
                if ("event".equals(optionalStringField(message, "type"))
                        && "output".equals(optionalStringField(message, "event"))
                        && category.equals(optionalStringField(message, "category"))
                        && message.contains(fragment)) {
                    iterator.remove();
                    return message;
                }
            }
            return null;
        }

        private static int responseRequestSequence(String message) {
            Matcher matcher = RESPONSE_REQUEST.matcher(message);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
        }

        private static String optionalStringField(String json, String name) {
            Pattern pattern =
                    Pattern.compile(
                            "\\\""
                                    + Pattern.quote(name)
                                    + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
            Matcher matcher = pattern.matcher(json);
            return matcher.find() ? matcher.group(1) : null;
        }

        private void write(String body) throws IOException {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            output.write(
                    ("Content-Length: " + payload.length + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            output.write(payload);
            output.flush();
        }

        private String read() throws IOException {
            int contentLength = -1;
            while (true) {
                String header = readHeaderLine();
                if (header.isEmpty()) {
                    break;
                }
                int colon = header.indexOf(':');
                if (colon <= 0) {
                    throw new IOException("Malformed DAP header: " + header);
                }
                String name = header.substring(0, colon).trim();
                String value = header.substring(colon + 1).trim();
                if ("Content-Length".equalsIgnoreCase(name)) {
                    contentLength = Integer.parseInt(value);
                }
            }

            if (contentLength < 0) {
                throw new IOException("DAP message has no Content-Length header");
            }

            byte[] body = input.readNBytes(contentLength);
            if (body.length != contentLength) {
                throw new EOFException(
                        "DAP body truncated: expected "
                                + contentLength
                                + " bytes, got "
                                + body.length);
            }
            return new String(body, StandardCharsets.UTF_8);
        }

        private String readHeaderLine() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int previous = -1;

            while (true) {
                int current = input.read();
                if (current < 0) {
                    throw new EOFException(
                            "DAP socket closed while reading headers");
                }
                if (previous == '\r' && current == '\n') {
                    byte[] bytes = line.toByteArray();
                    int length = Math.max(0, bytes.length - 1);
                    return new String(
                            bytes,
                            0,
                            length,
                            StandardCharsets.US_ASCII);
                }
                line.write(current);
                previous = current;
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
