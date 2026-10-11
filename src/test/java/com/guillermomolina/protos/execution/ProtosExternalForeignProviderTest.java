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
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosTestIntegers;
import com.guillermomolina.protos.cli.ProtosCli;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosActor;
import com.guillermomolina.protos.runtime.ProtosActorValueTransfer;
import com.guillermomolina.protos.runtime.ProtosEnvironmentValue;
import com.guillermomolina.protos.runtime.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.spi.foreign.ProtosForeignProviderPlugin;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * I085-A proof that an external foreign provider JAR, built against only the public SPI and the
 * Polyglot API, adds an invented import scheme without any Protos change.
 *
 * <p>The plugin JARs are compiled and packaged here, outside the Protos class path, with a
 * compilation class path holding only the core {@code spi.foreign} classes: neither Protos
 * internals nor the Polyglot API are reachable. Their module handle is an ordinary Java object
 * defined in the JAR, neither a Polyglot {@code Value} nor a {@code TruffleObject}, and every
 * operation on it is supplied by the plugin's value operations.
 */
class ProtosExternalForeignProviderTest {
    private static final String SERVICE =
            "META-INF/services/" + ProtosForeignProviderPlugin.class.getName();
    private static final String DEMO = "lib: import(\"inventado:demo\")\n";

    @TempDir static Path work;
    static Path invented;
    static Path second;
    static Path duplicateScheme;
    static Path duplicateId;
    static Path reservedScheme;

    @BeforeAll
    static void buildPluginJars() throws Exception {
        Path sources = Files.createDirectories(work.resolve("src"));
        Path classes = Files.createDirectories(work.resolve("classes"));
        List<String> plugins =
                List.of(
                        plugin(sources, "invented", "Invented", "invented", "inventado"),
                        plugin(sources, "second", "Second", "second", "otro"),
                        plugin(sources, "dupscheme", "DupScheme", "dup-scheme", "inventado"),
                        plugin(sources, "dupid", "DupId", "invented", "distinto"),
                        plugin(sources, "reserved", "Reserved", "reserved", "std"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<String> arguments = new ArrayList<>(
                List.of("--release", "21", "-proc:none", "-classpath", publicClassPath(), "-d",
                        classes.toString()));
        arguments.addAll(plugins);
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        assertEquals(
                0,
                javac.run(null, diagnostics, diagnostics, arguments.toArray(String[]::new)),
                diagnostics::toString);
        invented = jar(classes, "invented", "Invented");
        second = jar(classes, "second", "Second");
        duplicateScheme = jar(classes, "dupscheme", "DupScheme");
        duplicateId = jar(classes, "dupid", "DupId");
        reservedScheme = jar(classes, "reserved", "Reserved");
    }

    @Test
    void withoutTheExternalJarTheSchemeDoesNotExist() throws Exception {
        try (Fixture fixture = new Fixture(ProtosPolyglotRuntimeHost.open())) {
            assertSame(
                    ProtosForeignProviderRegistry.EMPTY, fixture.host.foreignProvidersForRuntime());
            assertNull(fixture.host.foreignProviderLoaderForTesting());
            assertEquals(
                    ProtosExecutionOutcome.State.FAILED,
                    fixture.run(DEMO + "lib.double(21)").state());
        }
        // An empty configuration is the ordinary host: no discovery and no class loader.
        try (ProtosPolyglotRuntimeHost host =
                ProtosPolyglotRuntimeHost.openWithForeignProviders(
                        ProtosForeignProviderConfiguration.trustedInProcess(List.of()))) {
            assertSame(ProtosForeignProviderRegistry.EMPTY, host.foreignProvidersForRuntime());
            assertNull(host.foreignProviderLoaderForTesting());
        }
    }

    @Test
    void configuredJarRoutesTheInventedSchemeThroughTheCommonSubstrate() throws Exception {
        try (Fixture fixture = new Fixture(invented)) {
            ProtosForeignProviderDescriptor descriptor =
                    fixture.host
                            .foreignProvidersForRuntime()
                            .lookupImportScheme("inventado")
                            .orElseThrow();
            assertEquals(
                    ProtosForeignProviderExecutionProfile.TRUSTED_IN_PROCESS,
                    descriptor.profile());
            assertInstanceOf(ProtosForeignPluginValueAdapter.class, descriptor.values());
            assertEquals(0, counter(fixture, "invented.Invented", "SESSIONS"));

            assertEquals(BigInteger.valueOf(42), integer(fixture.eval(DEMO + "lib.double(21)")));
            ProtosForeignModuleFacadeValue facade =
                    assertInstanceOf(
                            ProtosForeignModuleFacadeValue.class,
                            fixture.eval("import(\"inventado:demo\")"));
            assertSame(facade, fixture.eval("import(\"inventado:demo\")"));
            // The module handle is the plugin's own plain Java object, opaque to Protos.
            Object handle = facade.attachmentForRuntime().orElseThrow().target();
            assertFalse(handle instanceof Value);
            assertFalse(handle instanceof com.oracle.truffle.api.interop.TruffleObject);
            assertSame(fixture.host.foreignProviderLoaderForTesting(),
                    handle.getClass().getClassLoader());
            assertEquals("invented.Invented$Demo", handle.getClass().getName());
            assertSame(
                    facade,
                    fixture.root.actorModuleState()
                            .lookup(ProtosForeignModuleKey.encode("inventado", "demo"))
                            .orElseThrow()
                            .instance());
            assertEquals(1, counter(fixture, "invented.Invented", "ACQUISITIONS"));
            assertEquals(
                    ProtosExecutionOutcome.State.FAILED,
                    fixture.run("import(\"inventado:missing\")").state());

            // Another Actor owns another facade over its own session.
            ProtosActivation other = fixture.newActorActivation();
            Object otherFacade = fixture.eval(other, "import(\"inventado:demo\")");
            assertNotSame(facade, otherFacade);
            assertEquals(
                    BigInteger.valueOf(8),
                    integer(fixture.eval(other, "import(\"inventado:demo\").double(4)")));
            assertEquals(1, fixture.context.foreignProviderCompartmentCountForTesting());
            assertEquals(2, fixture.context.foreignProviderSessionCountForTesting());
            assertEquals(2, counter(fixture, "invented.Invented", "SESSIONS"));

            ProtosSignalException failure =
                    assertThrows(
                            ProtosSignalException.class,
                            () -> ProtosActorValueTransfer.snapshotValue(facade, fixture.root));
            assertSame(
                    fixture.standardError("NonTransferableValue"),
                    failure.error().parent().orElseThrow());
        }
    }

    @Test
    void closedSessionsInvalidateTheirReferencesAndNeverRebind() throws Exception {
        try (Fixture fixture = new Fixture(invented)) {
            ProtosForeignModuleFacadeValue facade =
                    (ProtosForeignModuleFacadeValue)
                            fixture.eval(DEMO + "lib.kept: lib.double\nlib");
            ProtosForeignProviderSessionBinding session =
                    facade.attachmentForRuntime().orElseThrow().session();

            ProtosActivation other = fixture.newActorActivation();
            ProtosForeignProviderSessionBinding otherSession =
                    ((ProtosForeignModuleFacadeValue)
                                    fixture.eval(other, "import(\"inventado:demo\")"))
                            .attachmentForRuntime()
                            .orElseThrow()
                            .session();
            fixture.actorOf(other).requestTerminationForRuntime();
            assertFalse(otherSession.isOpenForRuntime());
            assertTrue(session.isOpenForRuntime());
            assertEquals(1, counter(fixture, "invented.Invented", "CLOSES"));

            session.closeForRuntime();
            assertEquals(2, counter(fixture, "invented.Invented", "CLOSES"));
            assertEquals(
                    ProtosExecutionOutcome.State.FAILED,
                    fixture.run(DEMO + "lib.double(1)").state());
            assertEquals(
                    ProtosExecutionOutcome.State.FAILED,
                    fixture.run(DEMO + "lib.kept(1)").state());
            assertEquals(2, counter(fixture, "invented.Invented", "SESSIONS"));
        }
    }

    @Test
    void everyIntegerReachesThePluginAsABigIntegerAndRoundTripsExactly() throws Exception {
        BigInteger twoPow63 = BigInteger.ONE.shiftLeft(63);
        try (Fixture fixture = new Fixture(invented)) {
            // The plugin is not entered before its first use.
            assertEquals(0, counter(fixture, "invented.Invented", "SESSIONS"));
            String[] spellings = {
                "7", "9223372036854775807", "-9223372036854775808",
                "9223372036854775808", "-9223372036854775809", "0x1" + "0".repeat(25)};
            BigInteger[] values = {
                BigInteger.valueOf(7), BigInteger.valueOf(Long.MAX_VALUE),
                BigInteger.valueOf(Long.MIN_VALUE), twoPow63,
                twoPow63.negate().subtract(BigInteger.ONE), BigInteger.ONE.shiftLeft(100)};
            for (int index = 0; index < spellings.length; index++) {
                // The plugin rejects any non-BigInteger Integer argument, so success proves it.
                assertEquals(values[index],
                        integer(fixture.eval(DEMO + "lib.echo(" + spellings[index] + ")")),
                        spellings[index]);
                assertEquals(values[index],
                        integer(fixture.eval(
                                DEMO + "lib.echo(lib.echo(" + spellings[index] + "))")),
                        spellings[index]);
            }
            assertSame(ProtosBooleanValue.TRUE, fixture.eval(
                    DEMO + "lib.echo(9223372036854775808).parent() === BigInteger"));
            assertEquals(1, counter(fixture, "invented.Invented", "SESSIONS"));

            // An independent session (another Actor) has the same exact contract.
            ProtosActivation other = fixture.newActorActivation();
            assertEquals(twoPow63, integer(fixture.eval(
                    other, "import(\"inventado:demo\").echo(9223372036854775808)")));
            assertEquals(BigInteger.valueOf(-2), integer(fixture.eval(
                    other, "import(\"inventado:demo\").double(0 - 1)")));
            assertEquals(2, counter(fixture, "invented.Invented", "SESSIONS"));
            assertEquals(2, fixture.context.foreignProviderSessionCountForTesting());
        }
    }

    @Test
    void secondExternalSchemeRegistersAndUnusedProvidersStayUninitialized() throws Exception {
        try (Fixture fixture = new Fixture(invented, second)) {
            assertEquals(2, fixture.host.foreignProvidersForRuntime().size());
            assertEquals(BigInteger.valueOf(42), integer(fixture.eval(DEMO + "lib.double(21)")));
            assertEquals(1, counter(fixture, "invented.Invented", "SESSIONS"));
            assertEquals(0, counter(fixture, "second.Second", "SESSIONS"));
            assertEquals(1, fixture.context.foreignProviderCompartmentCountForTesting());

            assertEquals(
                    BigInteger.valueOf(10),
                    integer(fixture.eval("import(\"otro:demo\").double(5)")));
            assertEquals(1, counter(fixture, "second.Second", "SESSIONS"));
            assertEquals(2, fixture.context.foreignProviderCompartmentCountForTesting());
        }
    }

    @Test
    void misconfiguredProviderPathsAreRejectedBeforeAnyHostExists() throws Exception {
        Path empty = Files.createDirectories(work.resolve("empty"));
        for (List<Path> paths :
                List.of(
                        List.of(invented, duplicateScheme),
                        List.of(invented, duplicateId),
                        List.of(reservedScheme),
                        List.of(empty),
                        List.of(work.resolve("absent.jar")))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            ProtosPolyglotRuntimeHost.openWithForeignProviders(
                                    ProtosForeignProviderConfiguration.trustedInProcess(paths)),
                    paths::toString);
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ProtosPolyglotRuntimeHost.openWithForeignProviders(
                                ProtosForeignProviderConfiguration.trustedInProcess(
                                                List.of(invented))
                                        .withProviderOptions("unknown", java.util.Map.of())));
    }

    @Test
    void cliLoadsOnlyExplicitlyAuthorizedProviderPaths() {
        String source = "print(import(\"inventado:demo\").double(21))";
        assertEquals(
                "42\n",
                cli(0, "--foreign-provider-path", invented.toString(), "-e", source));
        assertEquals(
                "",
                cli(
                        2,
                        "--foreign-provider-path",
                        invented.toString(),
                        "format"));
        ByteArrayOutputStream ignored = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(ignored, true, StandardCharsets.UTF_8);
        assertTrue(
                new ProtosCli().run(new String[] {"-e", source}, InputStream.nullInputStream(),
                        sink, sink) != 0);
    }

    private static String cli(int expectedExit, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit =
                new ProtosCli()
                        .run(
                                args,
                                InputStream.nullInputStream(),
                                new PrintStream(out, true, StandardCharsets.UTF_8),
                                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(expectedExit, exit, () -> err.toString(StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    // ---- external fixture construction

    /** Writes one inert plugin source; it uses only the public SPI and the Polyglot API. */
    private static String plugin(
            Path sources, String pkg, String name, String id, String scheme) throws Exception {
        String source =
                """
                package %1$s;

                import com.guillermomolina.protos.spi.foreign.ProtosForeignArgumentValue;
                import com.guillermomolina.protos.spi.foreign.ProtosForeignPluginEnvironment;
                import com.guillermomolina.protos.spi.foreign.ProtosForeignPluginSession;
                import com.guillermomolina.protos.spi.foreign.ProtosForeignProviderPlugin;
                import com.guillermomolina.protos.spi.foreign.ProtosForeignValueClass;
                import com.guillermomolina.protos.spi.foreign.ProtosForeignValueOperations;
                import java.math.BigInteger;
                import java.util.List;
                import java.util.Set;
                import java.util.concurrent.atomic.AtomicInteger;

                public final class %2$s implements ProtosForeignProviderPlugin {
                    public static final AtomicInteger SESSIONS = new AtomicInteger();
                    public static final AtomicInteger ACQUISITIONS = new AtomicInteger();
                    public static final AtomicInteger CLOSES = new AtomicInteger();

                    /** Ordinary Java module handle with one operation. */
                    public static final class Demo {
                        long twice(long value) { return value * 2; }
                    }

                    /** The {@code double} member bound to its exact receiver. */
                    record Bound(Demo receiver) {}

                    /** The {@code echo} member: answers its D188 Integer argument unchanged. */
                    record Echo() {}

                    public String providerId() { return "%3$s"; }

                    public String scheme() { return "%4$s"; }

                    public String canonicalTarget(String target) {
                        if (!target.equals("demo")) {
                            throw new IllegalArgumentException("unknown module");
                        }
                        return target;
                    }

                    public ProtosForeignPluginSession openSession(
                            ProtosForeignPluginEnvironment environment) {
                        SESSIONS.incrementAndGet();
                        return new ProtosForeignPluginSession() {
                            public Object acquireModule(String target) {
                                ACQUISITIONS.incrementAndGet();
                                return new Demo();
                            }

                            public void close() { CLOSES.incrementAndGet(); }
                        };
                    }

                    public ProtosForeignValueOperations valueOperations() {
                        return new ProtosForeignValueOperations() {
                            public String language() { return "%3$s"; }

                            public ProtosForeignValueClass classify(
                                    ProtosForeignPluginSession session, Object handle) {
                                if (handle instanceof Long number) {
                                    return ProtosForeignValueClass.integral(
                                            BigInteger.valueOf(number));
                                }
                                if (handle instanceof BigInteger exact) {
                                    return ProtosForeignValueClass.integral(exact);
                                }
                                if (handle instanceof Bound || handle instanceof Echo) {
                                    return ProtosForeignValueClass.handle(null,
                                            Set.of(ProtosForeignValueClass.Capability.EXECUTABLE));
                                }
                                return ProtosForeignValueClass.handle(handle,
                                        Set.of(ProtosForeignValueClass.Capability.MEMBER_READ));
                            }

                            public boolean acceptsArgument(ProtosForeignArgumentValue argument) {
                                return argument.kind() == ProtosForeignValueClass.Kind.INTEGER;
                            }

                            public boolean canFaithfullyReadMember(
                                    ProtosForeignPluginSession session, Object handle, String name) {
                                return handle instanceof Demo
                                        && (name.equals("double") || name.equals("echo"));
                            }

                            public Object readMember(
                                    ProtosForeignPluginSession session, Object handle, String name) {
                                return name.equals("echo") ? new Echo() : new Bound((Demo) handle);
                            }

                            public Object execute(
                                    ProtosForeignPluginSession session,
                                    Object handle,
                                    List<ProtosForeignArgumentValue> arguments) {
                                Object argument = arguments.get(0).value();
                                if (!(argument instanceof BigInteger exact)) {
                                    throw new IllegalStateException(
                                            "D188 Integer argument is not a BigInteger");
                                }
                                if (handle instanceof Echo) {
                                    return exact;
                                }
                                return ((Bound) handle).receiver().twice(exact.longValueExact());
                            }
                        };
                    }
                }
                """
                        .formatted(pkg, name, id, scheme);
        Path file = Files.createDirectories(sources.resolve(pkg)).resolve(name + ".java");
        Files.writeString(file, source);
        return file.toString();
    }

    /** Only the core public SPI classes: neither Protos internals nor Polyglot are reachable. */
    private static String publicClassPath() throws Exception {
        Path protosClasses = codeSource(ProtosForeignProviderPlugin.class);
        String spi = ProtosForeignProviderPlugin.class.getPackageName().replace('.', '/');
        Path spiOnly = work.resolve("spi-only");
        Path target = Files.createDirectories(spiOnly.resolve(spi));
        try (Stream<Path> files = Files.list(protosClasses.resolve(spi))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.copy(file, target.resolve(file.getFileName()));
            }
        }
        return spiOnly.toString();
    }

    private static Path codeSource(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static Path jar(Path classes, String pkg, String name) throws Exception {
        Path jar = work.resolve(pkg + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(SERVICE));
            out.write((pkg + "." + name + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            try (Stream<Path> files = Files.list(classes.resolve(pkg))) {
                for (Path file : files.toList()) {
                    out.putNextEntry(new JarEntry(pkg + "/" + file.getFileName()));
                    out.write(Files.readAllBytes(file));
                    out.closeEntry();
                }
            }
        }
        return jar;
    }

    private static int counter(Fixture fixture, String className, String field) throws Exception {
        Class<?> type =
                Class.forName(className, false, fixture.host.foreignProviderLoaderForTesting());
        return ((java.util.concurrent.atomic.AtomicInteger) type.getField(field).get(null)).get();
    }

    private static BigInteger integer(Object value) {
        return ProtosTestIntegers.exact(value);
    }

    /** Hosted Process over a host configured with exactly the given external provider paths. */
    private static final class Fixture implements AutoCloseable {
        final ProtosPrelude prelude;
        final ProtosStandaloneProcessBootstrap.Result bootstrap;
        final ProtosPolyglotRuntimeHost host;
        final ProtosPolyglotProcessContext context;
        final ProtosActivation root;

        Fixture(Path... providerPaths) throws Exception {
            this(
                    ProtosPolyglotRuntimeHost.openWithForeignProviders(
                            ProtosForeignProviderConfiguration.trustedInProcess(
                                    List.of(providerPaths))));
        }

        Fixture(ProtosPolyglotRuntimeHost host) throws Exception {
            this.host = host;
            prelude =
                    new ProtosCoreBootstrap()
                            .bootstrap(
                                    Path.of("protos", "lib", "core"),
                                    new ProtosModuleRuntimeTest.MemoryResolver());
            bootstrap =
                    ProtosStandaloneProcessBootstrap.create(
                            prelude, List.of(), exactEnvironmentDomain(), List.of(),
                            null, null, null, null, null, null, null);
            context =
                    host.hostProcess(
                            bootstrap.process(),
                            InputStream.nullInputStream(),
                            OutputStream.nullOutputStream(),
                            OutputStream.nullOutputStream());
            root = bootstrap.activation();
        }

        ProtosActivation newActorActivation() {
            ProtosActor actor =
                    bootstrap.process()
                            .createHostedActorForRuntime(
                                    new ProtosObjectValue(ProtosObjectValue.rootObject()));
            return prelude.newModuleActivation(
                    actor.moduleState(),
                    new ProtosModuleKey("entry"),
                    prelude.newExecutionContext(),
                    actor.executionDomain());
        }

        ProtosActor actorOf(ProtosActivation activation) {
            return activation.executionDomain().currentActorForRuntime().orElseThrow();
        }

        ProtosExecutionOutcome run(ProtosActivation actor, String source) {
            ProtosActivation activation =
                    prelude.newModuleActivation(
                            actor.actorModuleState(),
                            new ProtosModuleKey("entry"),
                            prelude.newExecutionContext(),
                            actor.executionDomain());
            return context.executeModuleSource(
                    ProtosModuleSource.fromCharacters(new ProtosModuleKey("entry"), source),
                    activation);
        }

        ProtosExecutionOutcome run(String source) {
            return run(root, source);
        }

        Object eval(ProtosActivation actor, String source) {
            ProtosExecutionOutcome outcome = run(actor, source);
            assertEquals(
                    ProtosExecutionOutcome.State.COMPLETED,
                    outcome.state(),
                    () -> source + "\nfailed with " + outcome.error());
            return outcome.value();
        }

        Object eval(String source) {
            return eval(root, source);
        }

        ProtosObjectValue standardError(String name) {
            return (ProtosObjectValue) prelude.bindings().readLocalSlot(name).orElseThrow();
        }

        @Override
        public void close() {
            try {
                bootstrap.process().requestTerminationForRuntime();
            } finally {
                host.close();
            }
        }
    }

    private static ProtosEnvironmentValue.NativeNameDomain exactEnvironmentDomain() {
        return new ProtosEnvironmentValue.NativeNameDomain() {
            @Override
            public boolean sameCapturedName(String left, String right) {
                return left.equals(right);
            }

            @Override
            public boolean isQueryRepresentable(String name) {
                return !name.contains("=") && name.indexOf('\0') < 0;
            }

            @Override
            public boolean matchesQuery(String captured, String query) {
                return captured.equals(query);
            }
        };
    }
}
