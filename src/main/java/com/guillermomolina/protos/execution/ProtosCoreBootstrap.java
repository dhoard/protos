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

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosModuleKey;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSemanticTransferFamily;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ProtosCoreBootstrap {
    private final ProtosSourceFileLoader sourceLoader;
    private final List<ProtosSemanticTransferFamily> semanticTransferFamilies;

    public ProtosCoreBootstrap() {
        this(new ProtosSourceFileLoader());
    }

    ProtosCoreBootstrap(ProtosSourceFileLoader sourceLoader) {
        this(sourceLoader, standardSemanticTransferFamilies());
    }

    private ProtosCoreBootstrap(
            ProtosSourceFileLoader sourceLoader,
            List<ProtosSemanticTransferFamily> semanticTransferFamilies) {
        this.sourceLoader =
                Objects.requireNonNull(sourceLoader, "sourceLoader");
        this.semanticTransferFamilies = List.copyOf(semanticTransferFamilies);
    }

    /**
     * PLAT051 test seam: a bootstrap whose Prelude authorizes exactly {@code families} instead of
     * the standard set. Authority still enters only through Prelude construction.
     */
    static ProtosCoreBootstrap withSemanticTransferFamiliesForTesting(
            List<ProtosSemanticTransferFamily> families) {
        return new ProtosCoreBootstrap(new ProtosSourceFileLoader(), families);
    }

    /**
     * The one Regex family descriptor (PLAT051-B). The same exact identity is authorized by every
     * standard Prelude and bound into the Regex module's private minting facility.
     */
    private static final ProtosRegexSemanticTransferFamily REGEX_FAMILY =
            new ProtosRegexSemanticTransferFamily();

    /**
     * Standard Library families authorized for PLAT051 semantic-value transfer. Each entry must be
     * an explicit semantic-portability contract of its owning standard module.
     */
    private static List<ProtosSemanticTransferFamily> standardSemanticTransferFamilies() {
        return List.of(REGEX_FAMILY);
    }

    public ProtosPrelude bootstrap(Path coreDirectory) throws IOException {
        return bootstrap(coreDirectory, ProtosModuleResolver.rejecting());
    }

    public ProtosPrelude bootstrap(Path coreDirectory, ProtosModuleResolver moduleResolver) throws IOException {
        Objects.requireNonNull(coreDirectory, "coreDirectory");
        Objects.requireNonNull(moduleResolver, "moduleResolver");

        if (ProtosPolyglotExecutionContext.hasEnteredContextForRuntime()) {
            return bootstrapEntered(coreDirectory, moduleResolver);
        }

        try (ProtosPolyglotExecutionContext bootstrapHost =
                ProtosPolyglotExecutionContext.open(
                        java.io.InputStream.nullInputStream(),
                        java.io.OutputStream.nullOutputStream(),
                        java.io.OutputStream.nullOutputStream())) {
            try {
                return bootstrapHost.callEntered(
                        () -> {
                            try {
                                return bootstrapEntered(coreDirectory, moduleResolver);
                            } catch (IOException failure) {
                                throw new java.io.UncheckedIOException(failure);
                            }
                        });
            } catch (java.io.UncheckedIOException failure) {
                throw failure.getCause();
            }
        }
    }

    private ProtosPrelude bootstrapEntered(
            Path coreDirectory,
            ProtosModuleResolver moduleResolver)
            throws IOException {
        publishStandardRoot(coreDirectory);

        ProtosObjectValue bootstrapContext =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosActivation bootstrapActivation =
                new ProtosActivation(
                        bootstrapContext,
                        List.of(),
                        bootstrapContext);

        sourceLoader
                .load(coreDirectory.resolve("Context.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("Number.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("Integer.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("Float.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("Error.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("InvalidReturn.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("error_taxonomy.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("Array.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("Any.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("Capture.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("String.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("Encoding.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("Map.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("IdentityMap.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("Path.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("IpAddress.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("IpEndpoint.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("Network.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("TcpConnection.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("TcpListener.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("Future.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("Actor.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("Process.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("TextReader.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("TextWriter.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("import.protos"))
                .call(bootstrapActivation);
        sourceLoader
                .load(coreDirectory.resolve("Bytes.protos"))
                .call(bootstrapActivation);

        Object contextBinding =
                bootstrapContext
                        .readLocalSlot("Context")
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Core bootstrap did not define Context"));
        if (!(contextBinding instanceof ProtosObjectValue contextPrototype)) {
            throw new IllegalStateException(
                    "Core Context binding is not an ordinary object");
        }
        if (contextPrototype.parent().orElse(null)
                != ProtosObjectValue.rootObject()) {
            throw new IllegalStateException(
                    "Core Context prototype must delegate directly to Object");
        }

        ProtosObjectValue numberPrototype =
                requirePrototype(
                        bootstrapContext, "Number", ProtosObjectValue.rootObject());
        ProtosObjectValue integerPrototype =
                requirePrototype(bootstrapContext, "Integer", numberPrototype);
        ProtosObjectValue floatPrototype =
                requirePrototype(bootstrapContext, "Float", numberPrototype);
        ProtosStandardNumberEqualityProtocol.install(numberPrototype);
        ProtosStandardNumberOrderingProtocol.install(numberPrototype);
        ProtosStandardHashSupport.installNumberHash(numberPrototype);
        ProtosStandardIntegerProtocol.install(integerPrototype);
        ProtosStandardFloatProtocol.install(floatPrototype);
        ProtosStandardNumericConversionProtocol.install(integerPrototype, floatPrototype);

        Object errorBinding =
                bootstrapContext
                        .readLocalSlot("Error")
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Core bootstrap did not define Error"));
        if (!(errorBinding instanceof ProtosObjectValue errorPrototype)) {
            throw new IllegalStateException(
                    "Core Error binding is not an ordinary object");
        }
        if (errorPrototype.parent().orElse(null)
                != ProtosObjectValue.rootObject()) {
            throw new IllegalStateException(
                    "Core Error prototype must delegate directly to Object");
        }
        ProtosStandardErrorProtocol.install(errorPrototype);

        Object invalidReturnBinding =
                bootstrapContext
                        .readLocalSlot("InvalidReturn")
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Core bootstrap did not define InvalidReturn"));
        if (!(invalidReturnBinding
                instanceof ProtosObjectValue invalidReturnPrototype)) {
            throw new IllegalStateException(
                    "Core InvalidReturn binding is not an ordinary object");
        }
        if (invalidReturnPrototype.parent().orElse(null)
                != errorPrototype) {
            throw new IllegalStateException(
                    "Core InvalidReturn prototype must delegate directly to Error");
        }
        ProtosCoreErrorTaxonomy.validate(bootstrapContext, errorPrototype);

        Object arrayBinding =
                bootstrapContext
                        .readLocalSlot("Array")
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Core bootstrap did not define Array"));
        if (!(arrayBinding instanceof ProtosObjectValue arrayPrototype)) {
            throw new IllegalStateException(
                    "Core Array binding is not an ordinary object");
        }
        if (arrayPrototype.parent().orElse(null)
                != ProtosObjectValue.rootObject()) {
            throw new IllegalStateException(
                    "Core Array prototype must delegate directly to Object");
        }
        ProtosStandardArrayProtocol.install(arrayPrototype);

        requirePrototype(
                bootstrapContext, "Any", ProtosObjectValue.rootObject());
        requirePrototype(
                bootstrapContext, "Capture", ProtosObjectValue.rootObject());

        ProtosObjectValue stringPrototype =
                requirePrototype(
                        bootstrapContext, "String", ProtosObjectValue.rootObject());
        ProtosStandardStringProtocol.install(stringPrototype);
        ProtosStandardHashSupport.installStringHash(stringPrototype);
        ProtosObjectValue mapPrototype = requirePrototype(bootstrapContext, "Map", ProtosObjectValue.rootObject());
        ProtosStandardMapProtocol.install(mapPrototype);
        ProtosObjectValue identityMapPrototype = requirePrototype(bootstrapContext, "IdentityMap", ProtosObjectValue.rootObject());
        ProtosStandardIdentityMapProtocol.install(identityMapPrototype);
        ProtosObjectValue pathPrototype = requirePrototype(bootstrapContext, "Path", ProtosObjectValue.rootObject());
        // The private kernel stays in the bootstrap context, where the Path source closures
        // resolve it; it is never published through the Prelude.
        ProtosObjectValue pathKernel =
                requirePrototype(bootstrapContext, "_corePathKernel", ProtosObjectValue.rootObject());
        ProtosStandardPathProtocol.install(pathPrototype, pathKernel);
        ProtosObjectValue ipAddressPrototype =
                requirePrototype(
                        bootstrapContext, "IpAddress", ProtosObjectValue.rootObject());
        ProtosStandardIpAddressProtocol.install(ipAddressPrototype);
        ProtosObjectValue ipEndpointPrototype =
                requirePrototype(
                        bootstrapContext, "IpEndpoint", ProtosObjectValue.rootObject());
        ProtosStandardIpEndpointProtocol.install(ipEndpointPrototype, ipAddressPrototype);
        // D172: the canonical IP families are runtime-retained standard objects published through
        // std:network modules, not public Prelude bindings.
        bootstrapContext.removeLocalSlot("IpAddress");
        bootstrapContext.removeLocalSlot("IpEndpoint");
        ProtosObjectValue networkPrototype =
                requirePrototype(
                        bootstrapContext, "Network", ProtosObjectValue.rootObject());
        ProtosStandardNetworkProtocol.install(networkPrototype);
        ProtosObjectValue tcpConnectionPrototype =
                requirePrototype(
                        bootstrapContext,
                        "_coreTcpConnectionPrototype",
                        ProtosObjectValue.rootObject());
        ProtosStandardTcpConnectionProtocol.install(tcpConnectionPrototype);
        bootstrapContext.removeLocalSlot("_coreTcpConnectionPrototype");
        ProtosObjectValue tcpListenerPrototype =
                requirePrototype(
                        bootstrapContext,
                        "_coreTcpListenerPrototype",
                        ProtosObjectValue.rootObject());
        ProtosStandardTcpListenerProtocol.install(tcpListenerPrototype);
        bootstrapContext.removeLocalSlot("_coreTcpListenerPrototype");
        ProtosObjectValue futurePrototype = requirePrototype(bootstrapContext, "Future", ProtosObjectValue.rootObject());
        ProtosStandardFutureProtocol.install(futurePrototype);
        ProtosObjectValue processObject =
                requirePrototype(
                        bootstrapContext, "Process", ProtosObjectValue.rootObject());
        ProtosStandardProcessProtocol.install(processObject);
        ProtosObjectValue actorObject =
                requirePrototype(
                        bootstrapContext, "Actor", ProtosObjectValue.rootObject());
        ProtosObjectValue actorRefPrototype =
                requirePrototype(
                        bootstrapContext,
                        "_coreActorRefPrototype",
                        ProtosObjectValue.rootObject());
        ProtosObjectValue groupRefPrototype =
                requirePrototype(
                        bootstrapContext,
                        "_coreGroupRefPrototype",
                        ProtosObjectValue.rootObject());
        ProtosObjectValue sendOperationPrototype =
                requirePrototype(
                        bootstrapContext,
                        "_coreSendOperationPrototype",
                        ProtosObjectValue.rootObject());
        bootstrapContext.removeLocalSlot("_coreActorRefPrototype");
        bootstrapContext.removeLocalSlot("_coreGroupRefPrototype");
        bootstrapContext.removeLocalSlot("_coreSendOperationPrototype");
        ProtosObjectValue textReaderFactory =
                requirePrototype(
                        bootstrapContext, "TextReader", ProtosObjectValue.rootObject());
        ProtosObjectValue textWriterFactory =
                requirePrototype(
                        bootstrapContext, "TextWriter", ProtosObjectValue.rootObject());
        ProtosObjectValue importFacility =
                requirePrototype(
                        bootstrapContext, "import", ProtosObjectValue.rootObject());
        ProtosModuleRuntime moduleRuntime = new ProtosModuleRuntime(moduleResolver);
        ProtosStandardActorProtocol actorProtocol =
                new ProtosStandardActorProtocol(
                        moduleRuntime, actorRefPrototype, sendOperationPrototype);
        actorProtocol.installGroupRefPrototype(groupRefPrototype);
        actorProtocol.installActorObject(actorObject);
        ProtosStandardImportProtocol.installImportFacility(importFacility, moduleRuntime);

        // Bytes is standardized but intentionally not a required Core-prelude binding.
        ProtosObjectValue bufferedBytesPrototype =
                requirePrototype(
                        bootstrapContext, "Bytes", ProtosObjectValue.rootObject());
        bootstrapContext.removeLocalSlot("Bytes");
        ProtosStandardBytesProtocol.install(bufferedBytesPrototype);

        ProtosObjectValue encodingPrototype =
                requirePrototype(
                        bootstrapContext, "Encoding", ProtosObjectValue.rootObject());
        ProtosStandardEncodingProtocol.install(
                encodingPrototype, bufferedBytesPrototype);

        ProtosStandardTextReaderProtocol.installFactory(
                textReaderFactory, bootstrapActivation);
        ProtosStandardTextWriterProtocol.installFactory(
                textWriterFactory, bootstrapActivation);
        // D167: the buffered byte factories are std:io standard-module members, not Core sources
        // or Prelude bindings.
        ProtosObjectValue bufferedReaderFactory =
                ProtosStandardBufferedByteIoProtocol.createReaderFactory(
                        bufferedBytesPrototype, bootstrapActivation);
        ProtosObjectValue bufferedWriterFactory =
                ProtosStandardBufferedByteIoProtocol.createWriterFactory(
                        bufferedBytesPrototype, bootstrapActivation);

        if (bootstrapContext.hasLocalSlot("_coreRootObject")) {
            throw new IllegalStateException(
                    "Core bootstrap root seed already exists");
        }
        bootstrapContext.createLocalSlot(
                "_coreRootObject",
                ProtosObjectValue.rootObject());
        try {
            sourceLoader
                    .load(coreDirectory.resolve("prelude.protos"))
                    .call(bootstrapActivation);
        } finally {
            bootstrapContext.removeLocalSlot("_coreRootObject");
        }
        ProtosObjectValue preludeBindings =
                requirePrototype(
                        bootstrapContext, "_corePreludeBindings", contextPrototype);
        bootstrapContext.removeLocalSlot("_corePreludeBindings");

        freezeSharedStandardGraph(
                bootstrapContext,
                preludeBindings,
                bufferedBytesPrototype,
                actorRefPrototype,
                groupRefPrototype,
                sendOperationPrototype,
                tcpConnectionPrototype,
                tcpListenerPrototype,
                ipAddressPrototype,
                ipEndpointPrototype,
                bufferedReaderFactory,
                bufferedWriterFactory);
        validateFrozenStandardGraph(
                bootstrapContext,
                preludeBindings,
                bufferedBytesPrototype,
                actorRefPrototype,
                groupRefPrototype,
                sendOperationPrototype,
                tcpConnectionPrototype,
                tcpListenerPrototype,
                ipAddressPrototype,
                ipEndpointPrototype,
                bufferedReaderFactory,
                bufferedWriterFactory);

        return new ProtosPrelude(
                preludeBindings,
                contextPrototype,
                bufferedBytesPrototype,
                actorRefPrototype,
                tcpConnectionPrototype,
                tcpListenerPrototype,
                ipAddressPrototype,
                ipEndpointPrototype,
                standardModuleMembers(
                        ipAddressPrototype,
                        ipEndpointPrototype,
                        bufferedReaderFactory,
                        bufferedWriterFactory),
                semanticTransferFamilies);
    }

    /**
     * Exact runtime-owned initial members of standard modules (D172, D167, AUD006-A3, D187,
     * LIB015-B1, LIB015-C1, LIB020-A, LIB020-B, LIB021-A).
     *
     * <p>This is configuration for the general module-member seam in {@link ProtosPrelude}; module
     * creation itself has no knowledge of these keys.
     */
    private static Map<ProtosModuleKey, Map<String, ProtosObjectValue>> standardModuleMembers(
            ProtosObjectValue ipAddressPrototype,
            ProtosObjectValue ipEndpointPrototype,
            ProtosObjectValue bufferedReaderFactory,
            ProtosObjectValue bufferedWriterFactory) {
        // std:text/ANSI receives the same facilities as the modules that mint their families.
        ProtosObjectValue styleFacility = ProtosSealedFamilyFacility.createFacility();
        ProtosObjectValue styledTextFacility = ProtosSealedFamilyFacility.createFacility();
        return Map.ofEntries(
                Map.entry(
                        new ProtosModuleKey("std:network/IpAddresses"),
                        Map.of("IpAddress", ipAddressPrototype)),
                Map.entry(
                        new ProtosModuleKey("std:network/IpEndpoints"),
                        Map.of("IpEndpoint", ipEndpointPrototype)),
                Map.entry(
                        new ProtosModuleKey("std:io/BufferedReader"),
                        Map.of("BufferedReader", bufferedReaderFactory)),
                Map.entry(
                        new ProtosModuleKey("std:io/BufferedWriter"),
                        Map.of("BufferedWriter", bufferedWriterFactory)),
                Map.entry(
                        ProtosCommandLineArrayConstructionFacility.MODULE_KEY,
                        Map.of(
                                ProtosCommandLineArrayConstructionFacility.BOOTSTRAP_SLOT,
                                ProtosCommandLineArrayConstructionFacility.createFactory())),
                Map.entry(
                        ProtosRegexUnicodeFacility.MODULE_KEY,
                        Map.of(
                                ProtosRegexUnicodeFacility.BOOTSTRAP_SLOT,
                                ProtosRegexUnicodeFacility.createFacility(),
                                ProtosRegexSemanticTransferFamily.BOOTSTRAP_SLOT,
                                REGEX_FAMILY.createFacility())),
                Map.entry(
                        ProtosLoggingFacility.EVENT_MODULE_KEY,
                        Map.of(
                                ProtosLoggingFacility.EVENT_BOOTSTRAP_SLOT,
                                ProtosLoggingFacility.createEventFacility())),
                Map.entry(
                        ProtosLoggingFacility.TEXT_MODULE_KEY,
                        Map.of(
                                ProtosLoggingFacility.TEXT_BOOTSTRAP_SLOT,
                                ProtosLoggingFacility.createTextFacility())),
                Map.entry(
                        ProtosLoggingFacility.JSON_MODULE_KEY,
                        Map.of(
                                ProtosLoggingFacility.JSON_BOOTSTRAP_SLOT,
                                ProtosLoggingFacility.createTextFacility())),
                Map.entry(
                        ProtosForeignInteropFacility.MODULE_KEY,
                        Map.of(
                                ProtosForeignInteropFacility.BOOTSTRAP_SLOT,
                                ProtosForeignInteropFacility.createFacility())),
                Map.entry(
                        ProtosSealedFamilyFacility.STYLE_MODULE_KEY,
                        Map.of(
                                ProtosSealedFamilyFacility.STYLE_BOOTSTRAP_SLOT,
                                styleFacility)),
                Map.entry(
                        ProtosSealedFamilyFacility.STYLED_TEXT_MODULE_KEY,
                        Map.of(
                                ProtosSealedFamilyFacility.STYLED_TEXT_BOOTSTRAP_SLOT,
                                styledTextFacility)),
                Map.entry(
                        ProtosSealedFamilyFacility.ANSI_MODULE_KEY,
                        Map.of(
                                ProtosSealedFamilyFacility.STYLE_BOOTSTRAP_SLOT,
                                styleFacility,
                                ProtosSealedFamilyFacility.STYLED_TEXT_BOOTSTRAP_SLOT,
                                styledTextFacility)));
    }


    private void publishStandardRoot(Path coreDirectory) throws IOException {
        ProtosObjectValue object = ProtosObjectValue.rootObject();
        synchronized (object) {
            if (object.isFrozen()) {
                validatePublishedRoot(object);
                return;
            }
            if (!object.isOpen()) {
                throw new IllegalStateException(
                        "standard Object root must be open during unpublished bootstrap construction");
            }

            ProtosStandardObjectProtocol.install();
            installSourceBackedObjectBehavior(coreDirectory);
            ProtosStandardHashSupport.installObjectHash();
            ProtosStandardFutureProtocol.installObjectFuture();
            ProtosParallelRuntime.installObjectParallel();

            validateRootSurface(object);
            requireContextLocalRootExecutionProjection(object, "init");
            requireContextLocalRootExecutionProjection(object, "==");
            requireContextLocalRootExecutionProjection(object, "match");
            freezeSharedStandardGraph(object);
            validatePublishedRoot(object);
        }
    }

    private static void validateRootSurface(ProtosObjectValue object) {
        Set<String> expected =
                Set.of(
                        "call",
                        "hasSlot",
                        "slotValue",
                        "ifTrue",
                        "ifFalse",
                        "and",
                        "or",
                        "hash",
                        "future",
                        "parallel",
                        "parent",
                        "ensure",
                        "whileTrue",
                        "caseOf",
                        "init",
                        "==",
                        "match");
        if (!object.localSlotsSnapshot().keySet().containsAll(expected)) {
            throw new IllegalStateException(
                    "standard Object root publication surface is incomplete");
        }
        if (object.hasLocalSlot("!=")) {
            throw new IllegalStateException(
                    "standard Object root must not publish an independent != protocol");
        }
        validateExistingSourceBackedClosure(object, "init");
        validateExistingSourceBackedClosure(object, "==");
        validateExistingSourceBackedClosure(object, "match");
    }

    private static void requireContextLocalRootExecutionProjection(
            ProtosObjectValue object, String selector) {
        Object value =
                object.readLocalSlot(selector)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "standard Object." + selector + " is missing"));
        if (!(value instanceof ProtosClosureValue closure)
                || closure.definition() == null
                || closure.executionPlan().isEmpty()
                || closure.nativeBody().isPresent()) {
            throw new IllegalStateException(
                    "standard Object." + selector + " is not source-backed");
        }
        closure.requireContextLocalExecutionProjectionForRuntime();
    }

    private static void validateContextLocalRootExecutionProjection(
            ProtosObjectValue object, String selector) {
        Object value = object.readLocalSlot(selector).orElse(null);
        if (!(value instanceof ProtosClosureValue closure)
                || !closure.requiresContextLocalExecutionProjectionForRuntime()) {
            throw new IllegalStateException(
                    "standard Object."
                            + selector
                            + " is missing Context-local executable projection metadata");
        }
    }

    private static void validatePublishedRoot(ProtosObjectValue object) {
        validateRootSurface(object);
        if (!object.isFrozen()) {
            throw new IllegalStateException("standard Object root was not published frozen");
        }
        validateContextLocalRootExecutionProjection(object, "init");
        validateContextLocalRootExecutionProjection(object, "==");
        validateContextLocalRootExecutionProjection(object, "match");
        /*
         * I092 lowers canonical-Boolean ifTrue natively after publication and
         * relies on these frozen selections; never publish another behavior.
         */
        if (ProtosStandardBooleanProtocol.structuredCallbackKindForCanonicalSelection(
                        object.readLocalSlot("ifTrue").orElse(null), object)
                != ProtosStandardBooleanProtocol.StructuredCallbackKind.IF_TRUE) {
            throw new IllegalStateException(
                    "standard Object root must publish the standard ifTrue behavior");
        }
        if (!ProtosStandardObjectProtocol.isCanonicalStandardCallSelection(
                object.readLocalSlot("call").orElse(null), object)) {
            throw new IllegalStateException(
                    "standard Object root must publish the standard call behavior");
        }
        for (Object value : object.localSlotsSnapshot().values()) {
            if (!(value instanceof ProtosClosureValue closure) || !closure.isFrozen()) {
                throw new IllegalStateException(
                        "published standard Object behavior must be a frozen Closure");
            }
        }
    }

    /**
     * Seals only the implementation-owned standard graph being published by Core bootstrap.
     *
     * <p>This is not the Protos {@code freeze()} operation made recursive. It is a bootstrap
     * publication walk over standard objects that are about to be physically shared. Closure
     * semantic captures are included because a frozen Closure must not retain mutable shared
     * Protos state behind its structural shell.
     */
    private static void freezeSharedStandardGraph(Object... roots) {
        Set<ProtosObjectValue> visited =
                Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<ProtosObjectValue> pending = new ArrayDeque<>();
        enqueueStandardObjects(pending, roots);
        while (!pending.isEmpty()) {
            ProtosObjectValue object = pending.removeFirst();
            if (!visited.add(object)) {
                continue;
            }
            enqueueStandardObjects(
                    pending, object.localSlotsSnapshot().values().toArray());
            if (object instanceof ProtosClosureValue closure) {
                if (closure.definition() != null
                        && closure.executionPlan().isPresent()
                        && closure.nativeBody().isEmpty()) {
                    closure.requireContextLocalExecutionProjectionForRuntime();
                }
                enqueueStandardObjects(
                        pending, closure.capturedLexicalContexts().toArray());
                enqueueStandardObjects(pending, closure.capturedReceiver());
                closure.methodHome().ifPresent(pending::addLast);
            }
            object.freeze();
        }
    }

    private static void validateFrozenStandardGraph(Object... roots) {
        Set<ProtosObjectValue> visited =
                Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<ProtosObjectValue> pending = new ArrayDeque<>();
        enqueueStandardObjects(pending, roots);
        while (!pending.isEmpty()) {
            ProtosObjectValue object = pending.removeFirst();
            if (!visited.add(object)) {
                continue;
            }
            if (!object.isFrozen()) {
                throw new IllegalStateException(
                        "Core published mutable standard object: " + object.getClass().getName());
            }
            enqueueStandardObjects(
                    pending, object.localSlotsSnapshot().values().toArray());
            if (object instanceof ProtosClosureValue closure) {
                enqueueStandardObjects(
                        pending, closure.capturedLexicalContexts().toArray());
                enqueueStandardObjects(pending, closure.capturedReceiver());
                closure.methodHome().ifPresent(pending::addLast);
            }
        }
    }

    private static void enqueueStandardObjects(
            ArrayDeque<ProtosObjectValue> pending, Object... values) {
        for (Object value : values) {
            if (value instanceof ProtosObjectValue object) {
                pending.addLast(object);
            }
        }
    }


    private void installSourceBackedObjectBehavior(Path coreDirectory)
            throws IOException {
        ProtosObjectValue sourceContext =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosActivation sourceActivation =
                new ProtosActivation(sourceContext, List.of(), sourceContext);

        sourceLoader
                .load(coreDirectory.resolve("Object.protos"))
                .call(sourceActivation);

        ProtosClosureValue init =
                requireSourceBackedClosure(sourceContext, "_coreObjectInit");
        ProtosClosureValue equals =
                requireSourceBackedClosure(sourceContext, "_coreObjectEquals");
        ProtosClosureValue match =
                requireSourceBackedClosure(sourceContext, "_coreObjectMatch");
        ProtosClosureValue ifNull =
                requireSourceBackedClosure(sourceContext, "ifNull");
        ProtosClosureValue ifNotNull =
                requireSourceBackedClosure(sourceContext, "ifNotNull");

        sourceContext.removeLocalSlot("_coreObjectInit");
        sourceContext.removeLocalSlot("_coreObjectEquals");
        sourceContext.removeLocalSlot("_coreObjectMatch");
        sourceContext.removeLocalSlot("ifNull");
        sourceContext.removeLocalSlot("ifNotNull");
        if (!sourceContext.localSlotsSnapshot().isEmpty()) {
            throw new IllegalStateException(
                    "Core object source left unexpected bootstrap bindings");
        }
        sourceContext.freeze();

        ProtosObjectValue object = ProtosObjectValue.rootObject();
        synchronized (object) {
            validateExistingSourceBackedClosure(object, "init");
            validateExistingSourceBackedClosure(object, "==");
            validateExistingSourceBackedClosure(object, "match");
            validateExistingSourceBackedClosure(object, "ifNull");
            validateExistingSourceBackedClosure(object, "ifNotNull");
            if (!object.hasLocalSlot("init")) {
                object.createLocalSlot("init", init);
            }
            if (!object.hasLocalSlot("==")) {
                object.createLocalSlot("==", equals);
            }
            if (!object.hasLocalSlot("match")) {
                object.createLocalSlot("match", match);
            }
            if (!object.hasLocalSlot("ifNull")) {
                object.createLocalSlot("ifNull", ifNull);
            }
            if (!object.hasLocalSlot("ifNotNull")) {
                object.createLocalSlot("ifNotNull", ifNotNull);
            }
        }
    }

    private static ProtosClosureValue requireSourceBackedClosure(
            ProtosObjectValue sourceContext, String name) {
        Object value =
                sourceContext
                        .readLocalSlot(name)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Core object source did not define "
                                                        + name));
        if (!(value instanceof ProtosClosureValue closure)
                || closure.definition() == null
                || closure.executionPlan().isEmpty()
                || closure.nativeBody().isPresent()) {
            throw new IllegalStateException(
                    "Core " + name + " must be a source-backed Closure");
        }
        return closure;
    }

    private static void validateExistingSourceBackedClosure(
            ProtosObjectValue object, String selector) {
        Object existing = object.readLocalSlot(selector).orElse(null);
        if (existing == null) {
            return;
        }
        if (!(existing instanceof ProtosClosureValue closure)
                || closure.definition() == null
                || closure.executionPlan().isEmpty()
                || closure.nativeBody().isPresent()) {
            throw new IllegalStateException(
                    "Core Object."
                            + selector
                            + " is not installed from distributable Core source");
        }
    }

    private static ProtosObjectValue requirePrototype(
            ProtosObjectValue bootstrapContext,
            String name,
            Object expectedParent) {
        Object binding =
                bootstrapContext
                        .readLocalSlot(name)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Core bootstrap did not define " + name));
        if (!(binding instanceof ProtosObjectValue prototype)) {
            throw new IllegalStateException(
                    "Core " + name + " binding is not an ordinary object");
        }
        if (prototype.parent().orElse(null) != expectedParent) {
            throw new IllegalStateException(
                    "Core " + name + " prototype has the wrong delegation parent");
        }
        return prototype;
    }
}
