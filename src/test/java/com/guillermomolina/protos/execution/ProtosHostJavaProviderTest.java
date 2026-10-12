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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.execution.ProtosHostJavaFixtureTypes.Denied;
import com.guillermomolina.protos.execution.ProtosHostJavaFixtureTypes.Greeter;
import com.guillermomolina.protos.execution.ProtosHostJavaFixtureTypes.Lazy;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosActor;
import com.guillermomolina.protos.runtime.ProtosActorValueTransfer;
import com.guillermomolina.protos.runtime.ProtosBooleanValue;
import com.guillermomolina.protos.runtime.ProtosEnvironmentValue;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosRawForeignValue;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * I082-G end-to-end proof of the real production host-Java provider over an explicit test
 * catalogue of inert classes, through ordinary Protos {@code import("java:...")} syntax.
 */
class ProtosHostJavaProviderTest {
    private static final String GREETER = Greeter.class.getName();
    private static final String G = "G: import(\"java:" + GREETER + "\")\n";
    private static final Set<String> FOREIGN_ERROR_SLOTS =
            Set.of("language", "operation", "category", "foreignCategory", "message", "cause");

    static ProtosHostJavaCatalogue catalogue() throws Exception {
        return ProtosHostJavaCatalogue.builder()
                .exposeConstructor(Greeter.class, String.class)
                .exposeStaticMethod(Greeter.class, "createNamed", String.class)
                .exposeStaticMethod(Greeter.class, "staticEcho", String.class)
                .exposeStaticMethod(Greeter.class, "add", long.class, int.class)
                .exposeStaticMethod(Greeter.class, "half", double.class)
                .exposeStaticMethod(Greeter.class, "widen", float.class)
                .exposeStaticMethod(Greeter.class, "negate", boolean.class)
                .exposeStaticMethod(Greeter.class, "square", BigInteger.class)
                .exposeStaticMethod(Greeter.class, "tiny", byte.class)
                .exposeStaticMethod(Greeter.class, "nothing")
                .exposeStaticMethod(Greeter.class, "fail", String.class)
                .exposeStaticMethod(Greeter.class, "overloaded", int.class)
                .exposeInstanceMethod(Greeter.class, "name")
                .exposeInstanceMethod(Greeter.class, "join", String.class)
                .exposeInstanceMethod(Greeter.class, "self")
                .exposeInstanceMethod(Greeter.class, "sameAs", Greeter.class)
                .exposeInstanceMethod(Greeter.class, "increment")
                .exposeStaticMethod(Lazy.class, "ping")
                .build();
    }

    @Test
    void importedClassIsTheActorLocalFacadeWithStaticConstructorAndInstanceCalls()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            ProtosForeignModuleFacadeValue facade =
                    assertInstanceOf(
                            ProtosForeignModuleFacadeValue.class,
                            fixture.eval("import(\"java:" + GREETER + "\")"));
            assertSame(
                    facade,
                    fixture.root.actorModuleState()
                            .lookup(ProtosForeignModuleKey.encode("java", GREETER))
                            .orElseThrow()
                            .instance());
            assertSame(facade, fixture.eval("import(\"java:" + GREETER + "\")"));
            // The Java Class is private provider metadata, never the module identity; the facade's
            // only own member is its deliberately published constructor call.
            assertEquals(Set.of("call"), facade.localSlotsSnapshot().keySet());
            assertFalse(facade.attachmentForRuntime().orElseThrow().target() instanceof Class<?>);

            assertEquals("hi", text(fixture.eval(G + "G.staticEcho(\"hi\")")));
            assertEquals("ann", text(fixture.eval(G + "g: G(\"ann\")\ng.name()")));
            assertEquals("ann!", text(fixture.eval(G + "G(\"ann\").join(\"!\")")));
            assertEquals("bob", text(fixture.eval(G + "G.createNamed(\"bob\").name()")));
            assertEquals("cy?", text(fixture.eval(G + "g: G(\"cy\")\nj: g.join\nj(\"?\")")));
            assertInstanceOf(ProtosRawForeignValue.class, fixture.eval(G + "G(\"d\")"));

            // A different Actor owns a different facade over its own session.
            ProtosActivation other = fixture.newActorActivation();
            Object otherFacade = fixture.eval(other, "import(\"java:" + GREETER + "\")");
            assertNotSame(facade, otherFacade);
            assertNotSame(
                    facade.attachmentForRuntime().orElseThrow().session(),
                    ((ProtosForeignModuleFacadeValue) otherFacade)
                            .attachmentForRuntime()
                            .orElseThrow()
                            .session());
            assertEquals(1, fixture.context.foreignProviderCompartmentCountForTesting());
            assertEquals(2, fixture.context.foreignProviderSessionCountForTesting());
            // Host Java, not Espresso: no Context beyond the Protos Process Context exists.
            assertEquals(1, fixture.host.activeProcessContextCountForTesting());
        }
    }

    @Test
    void javaScalarsAdmitThroughD188AndArgumentsCrossOnlyLosslessly() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertEquals("5", integer(fixture.eval(G + "G.add(2, 3)")));
            assertEquals(1.5, ((ProtosFloatValue) fixture.eval(G + "G.half(3.0)")).value());
            assertEquals(0.25, ((ProtosFloatValue) fixture.eval(G + "G.widen(0.25)")).value());
            assertSame(ProtosBooleanValue.FALSE, fixture.eval(G + "G.negate(true)"));
            assertEquals(String.valueOf(BigInteger.TEN.pow(40)), integer(fixture.eval(G + "G.square(100000000000000000000)")));
            assertSame(ProtosNullValue.INSTANCE, fixture.eval(G + "G.nothing()"));
            assertEquals("-128", integer(fixture.eval(G + "G.tiny(-128)")));
            assertEquals("null", text(fixture.eval(G + "G(null).join(\"\")")));

            // Lossy or unsupported conversions fail before the exact Java member runs.
            int invocations = ProtosHostJavaFixtureTypes.INVOCATIONS.get();
            for (String call :
                    List.of(
                            "G.add(2, 3000000000)",
                            "G.add(2, 1.0)",
                            "G.add(\"2\", 1)",
                            "G.add(null, 1)",
                            "G.add(1)",
                            "G.tiny(128)",
                            "G.widen(0.1)",
                            "G.staticEcho(true)",
                            "G.overloaded(\"text\")")) {
                ProtosObjectValue error = fixture.failure(G + call);
                assertForeign(fixture, error);
                assertEquals("argument", text(error.readLocalSlot("category").orElseThrow()));
            }
            assertEquals(invocations, ProtosHostJavaFixtureTypes.INVOCATIONS.get());

            // A raw Java reference returns to Java as its exact object; other values never cross.
            assertSame(
                    ProtosBooleanValue.TRUE,
                    fixture.eval(G + "g: G(\"a\")\ng.sameAs(g)"));
            assertSame(
                    ProtosBooleanValue.FALSE,
                    fixture.eval(G + "g: G(\"a\")\ng.sameAs(G(\"a\"))"));
            invocations = ProtosHostJavaFixtureTypes.INVOCATIONS.get();
            // No D189 callback and no ordinary object crosses into a Java member: pre-entry.
            assertOrdinary(fixture, fixture.failure(G + "G.staticEcho((x) => { x })"));
            assertOrdinary(fixture, fixture.failure(G + "G.staticEcho(Array(1))"));
            assertOrdinary(fixture, fixture.failure(G + "G.staticEcho(G)"));
            assertEquals(invocations, ProtosHostJavaFixtureTypes.INVOCATIONS.get());
        }
    }

    @Test
    void javaObjectsKeepRawIdentityAndJavaEqualityIsNeverImported() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertSame(ProtosBooleanValue.TRUE, fixture.eval(G + "g: G(\"a\")\ng.self() === g"));
            assertSame(ProtosBooleanValue.TRUE, fixture.eval(G + "g: G(\"a\")\ng.self() == g"));
            assertSame(
                    ProtosBooleanValue.TRUE,
                    fixture.eval(G + "g: G(\"a\")\ng.self().hash() == g.hash()"));
            // Greeter.equals is total; distinct objects still stay distinct.
            assertSame(ProtosBooleanValue.FALSE, fixture.eval(G + "G(\"a\") === G(\"a\")"));
            assertSame(ProtosBooleanValue.FALSE, fixture.eval(G + "G(\"a\") == G(\"a\")"));
            assertEquals("2", integer(
                            fixture.eval(
                                    G + "mp: Map()\nmp[G(\"a\")] = 1\nmp[G(\"a\")] = 2\nmp.size()")));
            Object first = fixture.eval(G + "G.saved: G(\"keep\")\nG.saved.self()");
            Object second = fixture.eval(G + "G.saved.self()");
            assertNotSame(first, second);
            assertTrue(com.guillermomolina.protos.runtime.ProtosIdentity.identical(first, second));
        }
    }

    @Test
    void enteredJavaFailureIsAFreshSanitizedForeignError() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ProtosObjectValue error = fixture.failure(G + "G.fail(\"detail\")");
            assertNotSame(error, fixture.failure(G + "G.fail(\"detail\")"));
            assertForeign(fixture, error);
            assertEquals(FOREIGN_ERROR_SLOTS, error.localSlotsSnapshot().keySet());
            assertEquals("java", text(error.readLocalSlot("language").orElseThrow()));
            assertEquals("execute", text(error.readLocalSlot("operation").orElseThrow()));
            assertEquals("exception", text(error.readLocalSlot("category").orElseThrow()));
            assertEquals(
                    "java.lang.IllegalStateException",
                    text(error.readLocalSlot("foreignCategory").orElseThrow()));
            assertSame(ProtosNullValue.INSTANCE, error.readLocalSlot("message").orElseThrow());
            assertSame(ProtosNullValue.INSTANCE, error.readLocalSlot("cause").orElseThrow());
            for (Object value : error.localSlotsSnapshot().values()) {
                assertFalse(value instanceof Throwable);
                if (value instanceof ProtosStringValue string) {
                    assertFalse(string.value().contains("SECRET"));
                }
            }
        }
    }

    @Test
    void explicitInteropReadsOnlyTheExposedMemberSurfaceAndAddsNoVerbs() throws Exception {
        ProtosModuleRuntimeTest.MemoryResolver resolver =
                new ProtosModuleRuntimeTest.MemoryResolver()
                        .module(
                                "std:interop",
                                Files.readString(Path.of("protos", "lib", "interop.protos")));
        try (Fixture fixture = new Fixture(resolver)) {
            String i = "I: import(\"std:interop\")\n" + G;
            assertEquals(
                    "ann",
                    text(fixture.eval(i + "g: G(\"ann\")\nn: I.readMember(g, \"name\")\nn()")));
            assertEquals(
                    "hi",
                    text(fixture.eval(i + "e: I.readMember(G, \"staticEcho\")\nI.invoke(e, \"hi\")")));
            assertEquals("cy", text(fixture.eval(i + "I.invoke(G, \"cy\").name()")));
            // An unexposed member is an entered failure, never a reflective lookup.
            assertForeign(fixture, fixture.failure(i + "I.readMember(G(\"d\"), \"hashCode\")"));
            // The restricted baseline offers no instantiation or member write.
            assertOrdinary(fixture, fixture.failure(i + "I.instantiate(G, \"x\")"));
            assertOrdinary(fixture, fixture.failure(i + "I.writeMember(G(\"d\"), \"name\", \"x\")"));
        }
    }

    @Test
    void unadmittedClassesUnexposedMembersAndOverloadsFailClosed() throws Exception {
        try (Fixture fixture = new Fixture()) {
            int denied = ProtosHostJavaFixtureTypes.DENIED_INITIALIZATIONS.get();
            for (String target :
                    List.of(
                            Denied.class.getName(),
                            "java.lang.Runtime",
                            "java.lang.ProcessBuilder",
                            "java.lang.System",
                            "java.lang.Class",
                            "java.lang.ClassLoader",
                            "java.io.File",
                            "no.such.Clazz",
                            "",
                            GREETER + " ")) {
                assertEquals(
                        ProtosExecutionOutcome.State.FAILED,
                        fixture.run("import(\"java:" + target + "\")").state(),
                        target);
            }
            // Denial is a catalogue lookup: no loading, initialization, compartment, or session.
            assertEquals(denied, ProtosHostJavaFixtureTypes.DENIED_INITIALIZATIONS.get());
            assertEquals(0, fixture.context.foreignProviderCompartmentCountForTesting());
            assertEquals(0, fixture.context.foreignProviderSessionCountForTesting());

            int invocations = ProtosHostJavaFixtureTypes.INVOCATIONS.get();
            for (String access :
                    List.of(
                            "G.hidden()",
                            "G.getClass()",
                            "G.name()",
                            "G(\"a\").getClass()",
                            "G(\"a\").hashCode()",
                            "G(\"a\").equals(1)",
                            "G(\"a\").toString()",
                            "G(\"a\").createNamed(\"x\")",
                            "G(\"a\").wait()",
                            "G.staticEcho.getClass()")) {
                ProtosObjectValue error = fixture.failure(G + access);
                assertSame(fixture.standardError("SlotNotFound"), error.parent().orElseThrow(), access);
            }
            // Only the six receiver constructions of the probes above ran Java code.
            assertEquals(invocations + 6, ProtosHostJavaFixtureTypes.INVOCATIONS.get());
            // Ordinary assignment never becomes Java mutation: a raw reference gets no slots.
            assertOrdinary(fixture, fixture.failure(G + "g: G(\"a\")\ng.name = \"b\""));
        }

        // The catalogue never encodes an overload choice or an authority-bearing signature.
        ProtosHostJavaCatalogue.Builder builder =
                ProtosHostJavaCatalogue.builder()
                        .exposeStaticMethod(Greeter.class, "overloaded", int.class);
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.exposeStaticMethod(Greeter.class, "overloaded", String.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.exposeConstructor(Greeter.class, String.class)
                        .exposeConstructor(Greeter.class, String.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtosHostJavaCatalogue.builder()
                        .exposeInstanceMethod(Greeter.class, "getClass"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtosHostJavaCatalogue.builder()
                        .exposeInstanceMethod(Greeter.class, "add", long.class, int.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtosHostJavaCatalogue.builder()
                        .exposeStaticMethod(String.class, "valueOf", Object.class)
                        .build());
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtosHostJavaCatalogue.builder()
                        .exposeStaticMethod(java.util.Collections.class, "emptyList")
                        .build());
        assertThrows(
                NoSuchMethodException.class,
                () -> ProtosHostJavaCatalogue.builder()
                        .exposeStaticMethod(Greeter.class, "absent"));
    }

    @Test
    void javaProviderIsHostConfiguredRestrictedAndImportCannotChangeItsAuthority()
            throws Exception {
        ProtosForeignProviderDescriptor descriptor =
                ProtosHostJavaProvider.descriptor(catalogue());
        assertEquals(
                ProtosForeignProviderExecutionProfile.RESTRICTED_IN_PROCESS, descriptor.profile());
        assertInstanceOf(
                ProtosForeignProviderEnforcement.InProcessRestriction.class,
                descriptor.enforcement().orElseThrow());
        assertTrue(ProtosForeignProviderAdmission.rejection(descriptor).isEmpty());
        assertEquals("java", descriptor.importRoute().orElseThrow().scheme());
        try (Fixture fixture = new Fixture()) {
            fixture.eval(G + "G.staticEcho(\"x\")");
            ProtosForeignProviderDescriptor configured =
                    fixture.host.foreignProvidersForRuntime()
                            .lookupImportScheme("java")
                            .orElseThrow();
            assertEquals(
                    ProtosForeignProviderExecutionProfile.RESTRICTED_IN_PROCESS,
                    configured.profile());
            assertEquals(1, fixture.host.foreignProvidersForRuntime().size());
        }

        // The production provider reaches only preselected public members: no lookup by name,
        // class loading, reflection escape, ambient host access, Polyglot Context, or Espresso.
        for (String file : List.of("ProtosHostJavaProvider.java", "ProtosHostJavaCatalogue.java")) {
            String source =
                    Files.readString(
                            Path.of("src/main/java/com/guillermomolina/protos/execution", file));
            for (String forbidden :
                    List.of(
                            "Class.forName",
                            "loadClass",
                            "getClassLoader",
                            "ClassLoader",
                            "setAccessible",
                            "trySetAccessible",
                            "getDeclaredMethod",
                            "getDeclaredConstructor",
                            "getDeclaredField",
                            "getField",
                            "MethodHandles",
                            "Unsafe",
                            "ServiceLoader",
                            "HostAccess",
                            "PolyglotAccess",
                            "IOAccess",
                            "allowAllAccess",
                            "Context.newBuilder",
                            "ProcessBuilder",
                            "Runtime.getRuntime",
                            "System.getenv",
                            "System.getProperty",
                            "System.load",
                            "espresso",
                            "java.io.",
                            "java.nio.",
                            "java.net.")) {
                assertFalse(source.contains(forbidden), file + " must not use " + forbidden);
            }
        }
        String pom = Files.readString(Path.of("pom.xml"));
        assertFalse(pom.toLowerCase(java.util.Locale.ROOT).contains("espresso"));
        // The generic substrate stays free of host-Java reflection: it lives only in the provider.
        try (var files = Files.list(Path.of("src/main/java/com/guillermomolina/protos/execution"))) {
            for (Path file :
                    files.filter(path -> path.getFileName().toString().startsWith("ProtosForeign"))
                            .toList()) {
                assertFalse(Files.readString(file).contains("java.lang.reflect"), file.toString());
            }
        }
    }

    @Test
    void javaSessionsCloseWithActorAndProcessAndOldReferencesNeverRebind() throws Exception {
        ProtosForeignProviderSessionBinding rootSession;
        try (Fixture fixture = new Fixture()) {
            ProtosRawForeignValue saved =
                    assertInstanceOf(
                            ProtosRawForeignValue.class,
                            fixture.eval(G + "G.saved: G(\"keep\")\nG.saved"));
            ProtosForeignModuleFacadeValue facade =
                    (ProtosForeignModuleFacadeValue) fixture.eval("import(\"java:" + GREETER + "\")");
            rootSession = facade.attachmentForRuntime().orElseThrow().session();

            ProtosActivation other = fixture.newActorActivation();
            ProtosForeignModuleFacadeValue otherFacade =
                    (ProtosForeignModuleFacadeValue)
                            fixture.eval(other, "import(\"java:" + GREETER + "\")");
            ProtosForeignProviderSessionBinding otherSession =
                    otherFacade.attachmentForRuntime().orElseThrow().session();
            fixture.actorOf(other).requestTerminationForRuntime();
            assertFalse(otherSession.isOpenForRuntime());
            assertTrue(rootSession.isOpenForRuntime());

            int invocations = ProtosHostJavaFixtureTypes.INVOCATIONS.get();
            rootSession.closeForRuntime();
            assertOrdinary(fixture, fixture.failure(G + "G.saved.name()"));
            assertOrdinary(fixture, fixture.failure(G + "G(\"x\")"));
            assertOrdinary(fixture, fixture.failure(G + "G.staticEcho(\"x\")"));
            assertEquals(invocations, ProtosHostJavaFixtureTypes.INVOCATIONS.get());
            assertSame(rootSession, ((ProtosForeignHandle) saved.handleForRuntime()).session());
            assertEquals(1, fixture.context.foreignProviderCompartmentCountForTesting());
        }
        assertFalse(rootSession.isOpenForRuntime());
    }

    @Test
    void mutableJavaObjectsNeverCrossActorOrParallelBoundaries() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Object counter = fixture.eval(G + "G.counter: G(\"c\")\nG.counter.increment()\nG.counter");
            Object facade = fixture.eval(G + "G");
            Object method = fixture.eval(G + "G.counter.increment");
            for (Object value : List.of(counter, facade, method)) {
                ProtosSignalException failure =
                        assertThrows(
                                ProtosSignalException.class,
                                () ->
                                        ProtosActorValueTransfer.snapshotValue(
                                                value, fixture.activation()));
                assertSame(
                        fixture.standardError("NonTransferableValue"),
                        failure.error().parent().orElseThrow());
                assertEquals("NonParallel", parallelCopyFailure(value, fixture));
            }
            // The Java state stays owned by its Actor session and was never copied.
            assertEquals("2", integer(fixture.eval(G + "G.counter.increment()")));
        }
    }

    @Test
    void configuredButUnusedJavaProviderAndDefaultHostStayZeroUse() throws Exception {
        int lazy = ProtosHostJavaFixtureTypes.LAZY_INITIALIZATIONS.get();
        ProtosModuleRuntimeTest.MemoryResolver resolver =
                new ProtosModuleRuntimeTest.MemoryResolver().module("lib", "value: 42");
        try (Fixture fixture = new Fixture(resolver)) {
            assertEquals("42", integer(fixture.eval("import(\"lib\").value")));
            assertEquals(
                    ProtosExecutionOutcome.State.FAILED,
                    fixture.run("import(\"test:foo\")").state());
            assertTrue(resolver.resolvedSpecifiers.contains("lib"));
            assertTrue(resolver.resolvedSpecifiers.contains("test:foo"));
            assertFalse(resolver.resolvedSpecifiers.stream().anyMatch(s -> s.startsWith("java:")));
            assertEquals(0, fixture.context.foreignProviderCompartmentCountForTesting());
            assertEquals(0, fixture.context.foreignProviderSessionCountForTesting());
            assertEquals(1, fixture.host.activeProcessContextCountForTesting());
            assertEquals(lazy, ProtosHostJavaFixtureTypes.LAZY_INITIALIZATIONS.get());

            // Importing an admitted class still initializes nothing until a member runs.
            fixture.eval("import(\"java:" + Lazy.class.getName() + "\")");
            assertEquals(lazy, ProtosHostJavaFixtureTypes.LAZY_INITIALIZATIONS.get());
            assertEquals(
                    "pong",
                    text(fixture.eval("import(\"java:" + Lazy.class.getName() + "\").ping()")));
            assertEquals(lazy + 1, ProtosHostJavaFixtureTypes.LAZY_INITIALIZATIONS.get());
        }

        // The ordinary host configures no Java authority: java: is not a route at all.
        ProtosModuleRuntimeTest.MemoryResolver plain = new ProtosModuleRuntimeTest.MemoryResolver();
        try (Fixture fixture = new Fixture(plain, null)) {
            assertSame(ProtosForeignProviderRegistry.EMPTY, fixture.host.foreignProvidersForRuntime());
            assertEquals(
                    ProtosExecutionOutcome.State.FAILED,
                    fixture.run("import(\"java:" + GREETER + "\")").state());
            assertEquals(List.of("java:" + GREETER), plain.resolvedSpecifiers);
            assertEquals(0, fixture.context.foreignProviderCompartmentCountForTesting());
            assertEquals(0, fixture.context.foreignProviderSessionCountForTesting());
        }
    }

    private static String text(Object value) {
        return assertInstanceOf(ProtosStringValue.class, value).value();
    }

    private static String integer(Object value) {
        return com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(value);
    }

    private static void assertForeign(Fixture fixture, ProtosObjectValue error) {
        assertSame(fixture.standardError("ForeignError"), error.parent().orElseThrow());
    }

    /** Ordinary pre-entry failure: a Core Error, never ForeignError. */
    private static void assertOrdinary(Fixture fixture, ProtosObjectValue error) {
        assertFalse(error.parent().orElseThrow() == fixture.standardError("ForeignError"));
        assertTrue(error.localSlotsSnapshot().isEmpty());
    }

    private static String parallelCopyFailure(Object value, Fixture fixture) throws Exception {
        Class<?> transfer =
                Class.forName("com.guillermomolina.protos.execution.ProtosParallelRuntime$Transfer");
        Method copy =
                transfer.getDeclaredMethod(
                        "copy", Object.class, ProtosActivation.class, IdentityHashMap.class);
        copy.setAccessible(true);
        InvocationTargetException failure =
                assertThrows(
                        InvocationTargetException.class,
                        () ->
                                copy.invoke(
                                        null,
                                        value,
                                        fixture.activation(),
                                        new IdentityHashMap<Object, Object>()));
        return failure.getCause().getClass().getSimpleName();
    }

    /** Hosted Process over the production {@code openWithHostJava} or the default host. */
    private static final class Fixture implements AutoCloseable {
        final ProtosPrelude prelude;
        final ProtosStandaloneProcessBootstrap.Result bootstrap;
        final ProtosPolyglotRuntimeHost host;
        final ProtosPolyglotProcessContext context;
        final ProtosActivation root;
        private ProtosActivation current;

        Fixture() throws Exception {
            this(new ProtosModuleRuntimeTest.MemoryResolver());
        }

        Fixture(ProtosModuleRuntimeTest.MemoryResolver resolver) throws Exception {
            this(resolver, catalogue());
        }

        /** A null catalogue opens the ordinary {@link ProtosPolyglotRuntimeHost#open()} host. */
        Fixture(ProtosModuleRuntimeTest.MemoryResolver resolver, ProtosHostJavaCatalogue catalogue)
                throws Exception {
            prelude =
                    new ProtosCoreBootstrap().bootstrap(Path.of("protos", "lib", "core"), resolver);
            bootstrap =
                    ProtosStandaloneProcessBootstrap.create(
                            prelude, List.of(), exactEnvironmentDomain(), List.of(),
                            null, null, null, null, null, null, null);
            host =
                    catalogue == null
                            ? ProtosPolyglotRuntimeHost.open()
                            : ProtosPolyglotRuntimeHost.openWithHostJava(catalogue);
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

        /** Runs {@code source} as a fresh task of {@code actor}'s Actor; its module cache persists. */
        ProtosExecutionOutcome run(ProtosActivation actor, String source) {
            current =
                    prelude.newModuleActivation(
                            actor.actorModuleState(),
                            new ProtosModuleKey("entry"),
                            prelude.newExecutionContext(),
                            actor.executionDomain());
            return context.executeModuleSource(
                    ProtosModuleSource.fromCharacters(new ProtosModuleKey("entry"), source),
                    current);
        }

        ProtosExecutionOutcome run(String source) {
            return run(root, source);
        }

        Object eval(ProtosActivation actor, String source) {
            ProtosExecutionOutcome outcome = run(actor, source);
            assertEquals(
                    ProtosExecutionOutcome.State.COMPLETED,
                    outcome.state(),
                    () -> source + "\nfailed with " + describe(outcome.error()));
            return outcome.value();
        }

        Object eval(String source) {
            return eval(root, source);
        }

        ProtosObjectValue failure(String source) {
            ProtosExecutionOutcome outcome = run(source);
            assertEquals(ProtosExecutionOutcome.State.FAILED, outcome.state(), source);
            return outcome.error();
        }

        ProtosActivation activation() {
            return current;
        }

        /** Names the standard Error family and payload of a failure, for diagnostics. */
        String describe(ProtosObjectValue error) {
            if (error == null) {
                return "<no error>";
            }
            Object parent = error.parent().orElse(null);
            String family =
                    prelude.bindings().localSlotsSnapshot().entrySet().stream()
                            .filter(entry -> entry.getValue() == parent)
                            .map(java.util.Map.Entry::getKey)
                            .findFirst()
                            .orElse("<unknown>");
            return family + " " + error.localSlotsSnapshot();
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
