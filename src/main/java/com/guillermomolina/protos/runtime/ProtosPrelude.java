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

package com.guillermomolina.protos.runtime;

import com.guillermomolina.protos.runtime.modules.ProtosModuleKey;
import com.oracle.truffle.api.CompilerDirectives;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public final class ProtosPrelude {
    private final ProtosObjectValue bindings;
    private final ProtosObjectValue contextPrototype;
    private final ProtosObjectValue errorPrototype;
    private final Object arrayPrototypeBinding;
    private final ProtosObjectValue runtimeBytesPrototype;
    private final ProtosObjectValue runtimeActorRefPrototype;
    private final ProtosObjectValue runtimeTcpConnectionPrototype;
    private final ProtosObjectValue runtimeTcpListenerPrototype;
    private final ProtosObjectValue runtimeTcpAcceptorPrototype;
    private final ProtosObjectValue runtimeIpAddressPrototype;
    private final ProtosObjectValue runtimeIpEndpointPrototype;
    private final ProtosClosureValue runtimeFilesystemOpenMethod;
    private final ProtosObjectValue runtimeEntropyPrototype;
    private final ProtosObjectValue runtimeClockPrototype;
    private final Map<ProtosModuleKey, Map<String, ProtosObjectValue>> standardModuleMembers;
    private final Set<ProtosObjectValue> standardModuleMemberIdentities;
    private final Map<
            ProtosModuleKey,
            Map<String, Supplier<ProtosObjectValue>>>
            lazyStandardModuleMembers;
    private final Map<ProtosModuleKey, ProtosSemanticTransferFamily> semanticTransferFamilies;
    /*
     * Private, per-instance identity of the Process module runtime installed by this Prelude's
     * bootstrap behind its source-backed import facility. Bound at most once, never a binding or
     * slot.
     */
    private Object moduleRuntime;

    public ProtosPrelude(
            ProtosObjectValue bindings,
            ProtosObjectValue contextPrototype) {
        this(bindings, contextPrototype, null, null);
    }

    public ProtosPrelude(
            ProtosObjectValue bindings,
            ProtosObjectValue contextPrototype,
            ProtosObjectValue runtimeBytesPrototype,
            ProtosObjectValue runtimeActorRefPrototype) {
        this(
                bindings,
                contextPrototype,
                runtimeBytesPrototype,
                runtimeActorRefPrototype,
                null);
    }

    public ProtosPrelude(
            ProtosObjectValue bindings,
            ProtosObjectValue contextPrototype,
            ProtosObjectValue runtimeBytesPrototype,
            ProtosObjectValue runtimeActorRefPrototype,
            ProtosObjectValue runtimeTcpConnectionPrototype) {
        this(
                bindings,
                contextPrototype,
                runtimeBytesPrototype,
                runtimeActorRefPrototype,
                runtimeTcpConnectionPrototype,
                null);
    }

    public ProtosPrelude(
            ProtosObjectValue bindings,
            ProtosObjectValue contextPrototype,
            ProtosObjectValue runtimeBytesPrototype,
            ProtosObjectValue runtimeActorRefPrototype,
            ProtosObjectValue runtimeTcpConnectionPrototype,
            ProtosObjectValue runtimeTcpListenerPrototype) {
        this(
                bindings,
                contextPrototype,
                runtimeBytesPrototype,
                runtimeActorRefPrototype,
                runtimeTcpConnectionPrototype,
                runtimeTcpListenerPrototype,
                null,
                null,
                Map.of());
    }

    /**
     * Creates a Prelude that additionally retains the canonical IP families and the standard
     * initial members of exact standard modules.
     *
     * <p>{@code standardModuleMembers} maps one canonical {@link ProtosModuleKey} to the exact
     * members installed into every Actor-local module context created for that key before its
     * source executes. Every member must be a FROZEN runtime-owned standard object: it is shared by
     * all module instances of this Prelude, so it must carry no mutable module state.
     */
    public ProtosPrelude(
            ProtosObjectValue bindings,
            ProtosObjectValue contextPrototype,
            ProtosObjectValue runtimeBytesPrototype,
            ProtosObjectValue runtimeActorRefPrototype,
            ProtosObjectValue runtimeTcpConnectionPrototype,
            ProtosObjectValue runtimeTcpListenerPrototype,
            ProtosObjectValue runtimeIpAddressPrototype,
            ProtosObjectValue runtimeIpEndpointPrototype,
            Map<ProtosModuleKey, Map<String, ProtosObjectValue>> standardModuleMembers) {
        this(
                bindings,
                contextPrototype,
                runtimeBytesPrototype,
                runtimeActorRefPrototype,
                runtimeTcpConnectionPrototype,
                runtimeTcpListenerPrototype,
                runtimeIpAddressPrototype,
                runtimeIpEndpointPrototype,
                standardModuleMembers,
                List.of());
    }

    /**
     * Creates a Prelude that additionally authorizes the given bootstrap-provided Standard Library
     * semantic transfer families (PLAT051).
     *
     * <p>Authorization is by exact descriptor identity under its owning standard module key, at
     * most one family per key, fixed for the lifetime of this Prelude.
     */
    public ProtosPrelude(
            ProtosObjectValue bindings,
            ProtosObjectValue contextPrototype,
            ProtosObjectValue runtimeBytesPrototype,
            ProtosObjectValue runtimeActorRefPrototype,
            ProtosObjectValue runtimeTcpConnectionPrototype,
            ProtosObjectValue runtimeTcpListenerPrototype,
            ProtosObjectValue runtimeIpAddressPrototype,
            ProtosObjectValue runtimeIpEndpointPrototype,
            Map<ProtosModuleKey, Map<String, ProtosObjectValue>> standardModuleMembers,
            List<ProtosSemanticTransferFamily> semanticTransferFamilies) {
        this(
                bindings,
                contextPrototype,
                runtimeBytesPrototype,
                runtimeActorRefPrototype,
                runtimeTcpConnectionPrototype,
                runtimeTcpListenerPrototype,
                runtimeIpAddressPrototype,
                runtimeIpEndpointPrototype,
                standardModuleMembers,
                semanticTransferFamilies,
                Map.of());
    }

    /**
     * Creates a Prelude that additionally registers deferred standard initial members (LIB022-A).
     *
     * <p>{@code lazyStandardModuleMembers} maps one canonical {@link ProtosModuleKey} to member
     * suppliers that are stored without being invoked. Each supplier runs exactly once per
     * Actor-local module context created for its key, during that module's initialization, and
     * must answer a FROZEN object. The created object is private to that module instance: it is
     * not shared by the Prelude and is therefore not a standard-member transfer anchor. A member
     * name may not be registered both eagerly and lazily for the same module.
     */
    public ProtosPrelude(
            ProtosObjectValue bindings,
            ProtosObjectValue contextPrototype,
            ProtosObjectValue runtimeBytesPrototype,
            ProtosObjectValue runtimeActorRefPrototype,
            ProtosObjectValue runtimeTcpConnectionPrototype,
            ProtosObjectValue runtimeTcpListenerPrototype,
            ProtosObjectValue runtimeIpAddressPrototype,
            ProtosObjectValue runtimeIpEndpointPrototype,
            Map<ProtosModuleKey, Map<String, ProtosObjectValue>> standardModuleMembers,
            List<ProtosSemanticTransferFamily> semanticTransferFamilies,
            Map<ProtosModuleKey, Map<String, Supplier<ProtosObjectValue>>>
                    lazyStandardModuleMembers) {
        this(
                bindings,
                contextPrototype,
                runtimeBytesPrototype,
                runtimeActorRefPrototype,
                runtimeTcpConnectionPrototype,
                runtimeTcpListenerPrototype,
                runtimeIpAddressPrototype,
                runtimeIpEndpointPrototype,
                standardModuleMembers,
                semanticTransferFamilies,
                lazyStandardModuleMembers,
                null);
    }

    /**
     * Creates a Prelude that additionally retains the runtime-only TcpAcceptor protocol prototype
     * (I125-A). The prototype is omitted from public Prelude bindings.
     */
    public ProtosPrelude(
            ProtosObjectValue bindings,
            ProtosObjectValue contextPrototype,
            ProtosObjectValue runtimeBytesPrototype,
            ProtosObjectValue runtimeActorRefPrototype,
            ProtosObjectValue runtimeTcpConnectionPrototype,
            ProtosObjectValue runtimeTcpListenerPrototype,
            ProtosObjectValue runtimeIpAddressPrototype,
            ProtosObjectValue runtimeIpEndpointPrototype,
            Map<ProtosModuleKey, Map<String, ProtosObjectValue>> standardModuleMembers,
            List<ProtosSemanticTransferFamily> semanticTransferFamilies,
            Map<ProtosModuleKey, Map<String, Supplier<ProtosObjectValue>>>
                    lazyStandardModuleMembers,
            ProtosObjectValue runtimeTcpAcceptorPrototype) {
        this(
                bindings,
                contextPrototype,
                runtimeBytesPrototype,
                runtimeActorRefPrototype,
                runtimeTcpConnectionPrototype,
                runtimeTcpListenerPrototype,
                runtimeIpAddressPrototype,
                runtimeIpEndpointPrototype,
                standardModuleMembers,
                semanticTransferFamilies,
                lazyStandardModuleMembers,
                runtimeTcpAcceptorPrototype,
                null);
    }

    /**
     * Creates a Prelude that additionally retains the runtime-only source-backed Filesystem open
     * method (D241-B, I148). The method is omitted from public Prelude bindings, standard module
     * members and transfer families; it holds no Filesystem authority and is bound per capability.
     */
    public ProtosPrelude(
            ProtosObjectValue bindings,
            ProtosObjectValue contextPrototype,
            ProtosObjectValue runtimeBytesPrototype,
            ProtosObjectValue runtimeActorRefPrototype,
            ProtosObjectValue runtimeTcpConnectionPrototype,
            ProtosObjectValue runtimeTcpListenerPrototype,
            ProtosObjectValue runtimeIpAddressPrototype,
            ProtosObjectValue runtimeIpEndpointPrototype,
            Map<ProtosModuleKey, Map<String, ProtosObjectValue>> standardModuleMembers,
            List<ProtosSemanticTransferFamily> semanticTransferFamilies,
            Map<ProtosModuleKey, Map<String, Supplier<ProtosObjectValue>>>
                    lazyStandardModuleMembers,
            ProtosObjectValue runtimeTcpAcceptorPrototype,
            ProtosClosureValue runtimeFilesystemOpenMethod) {
        this(
                bindings,
                contextPrototype,
                runtimeBytesPrototype,
                runtimeActorRefPrototype,
                runtimeTcpConnectionPrototype,
                runtimeTcpListenerPrototype,
                runtimeIpAddressPrototype,
                runtimeIpEndpointPrototype,
                standardModuleMembers,
                semanticTransferFamilies,
                lazyStandardModuleMembers,
                runtimeTcpAcceptorPrototype,
                runtimeFilesystemOpenMethod,
                null);
    }

    /**
     * D243-C complete Prelude construction, additionally retaining the
     * private authority-free source-backed Entropy prototype.
     */
    public ProtosPrelude(
            ProtosObjectValue bindings,
            ProtosObjectValue contextPrototype,
            ProtosObjectValue runtimeBytesPrototype,
            ProtosObjectValue runtimeActorRefPrototype,
            ProtosObjectValue runtimeTcpConnectionPrototype,
            ProtosObjectValue runtimeTcpListenerPrototype,
            ProtosObjectValue runtimeIpAddressPrototype,
            ProtosObjectValue runtimeIpEndpointPrototype,
            Map<ProtosModuleKey, Map<String, ProtosObjectValue>>
                    standardModuleMembers,
            List<ProtosSemanticTransferFamily> semanticTransferFamilies,
            Map<ProtosModuleKey, Map<String, Supplier<ProtosObjectValue>>>
                    lazyStandardModuleMembers,
            ProtosObjectValue runtimeTcpAcceptorPrototype,
            ProtosClosureValue runtimeFilesystemOpenMethod,
            ProtosObjectValue runtimeEntropyPrototype) {
        this(
                bindings,
                contextPrototype,
                runtimeBytesPrototype,
                runtimeActorRefPrototype,
                runtimeTcpConnectionPrototype,
                runtimeTcpListenerPrototype,
                runtimeIpAddressPrototype,
                runtimeIpEndpointPrototype,
                standardModuleMembers,
                semanticTransferFamilies,
                lazyStandardModuleMembers,
                runtimeTcpAcceptorPrototype,
                runtimeFilesystemOpenMethod,
                runtimeEntropyPrototype,
                null);
    }

    public ProtosPrelude(
            ProtosObjectValue bindings,
            ProtosObjectValue contextPrototype,
            ProtosObjectValue runtimeBytesPrototype,
            ProtosObjectValue runtimeActorRefPrototype,
            ProtosObjectValue runtimeTcpConnectionPrototype,
            ProtosObjectValue runtimeTcpListenerPrototype,
            ProtosObjectValue runtimeIpAddressPrototype,
            ProtosObjectValue runtimeIpEndpointPrototype,
            Map<ProtosModuleKey, Map<String, ProtosObjectValue>>
                    standardModuleMembers,
            List<ProtosSemanticTransferFamily> semanticTransferFamilies,
            Map<ProtosModuleKey, Map<String, Supplier<ProtosObjectValue>>>
                    lazyStandardModuleMembers,
            ProtosObjectValue runtimeTcpAcceptorPrototype,
            ProtosClosureValue runtimeFilesystemOpenMethod,
            ProtosObjectValue runtimeEntropyPrototype,
            ProtosObjectValue runtimeClockPrototype) {
        if (runtimeFilesystemOpenMethod != null
                && (runtimeFilesystemOpenMethod.definition() == null
                        || runtimeFilesystemOpenMethod.nativeBody().isPresent())) {
            throw new IllegalArgumentException(
                    "runtime Filesystem open method must be a source-backed Closure");
        }
        this.runtimeFilesystemOpenMethod = runtimeFilesystemOpenMethod;
        this.runtimeEntropyPrototype = runtimeEntropyPrototype;
        this.runtimeClockPrototype = runtimeClockPrototype;
        requireFrozenDirectChildOfObject(runtimeClockPrototype, "Clock");
        this.semanticTransferFamilies = copySemanticTransferFamilies(semanticTransferFamilies);
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.contextPrototype =
                Objects.requireNonNull(contextPrototype, "contextPrototype");
        this.runtimeBytesPrototype = runtimeBytesPrototype;
        this.runtimeActorRefPrototype = runtimeActorRefPrototype;
        this.runtimeTcpConnectionPrototype = runtimeTcpConnectionPrototype;
        this.runtimeTcpListenerPrototype = runtimeTcpListenerPrototype;
        this.runtimeTcpAcceptorPrototype = runtimeTcpAcceptorPrototype;
        this.runtimeIpAddressPrototype = runtimeIpAddressPrototype;
        this.runtimeIpEndpointPrototype = runtimeIpEndpointPrototype;
        this.standardModuleMembers = copyStandardModuleMembers(standardModuleMembers);
        this.standardModuleMemberIdentities = identitiesOf(this.standardModuleMembers);
        this.lazyStandardModuleMembers =
                copyLazyStandardModuleMembers(
                        lazyStandardModuleMembers, this.standardModuleMembers);

        requireFrozenDirectChildOfObject(runtimeEntropyPrototype, "Entropy");
        requireFrozenDirectChildOfObject(runtimeIpAddressPrototype, "IpAddress");
        requireFrozenDirectChildOfObject(runtimeIpEndpointPrototype, "IpEndpoint");

        if (runtimeTcpConnectionPrototype != null
                && (!runtimeTcpConnectionPrototype.isFrozen()
                        || runtimeTcpConnectionPrototype.parent().orElse(null)
                                != ProtosObjectValue.rootObject())) {
            throw new IllegalArgumentException(
                    "runtime TcpConnection prototype must be a frozen direct child of Object");
        }

        if (runtimeTcpListenerPrototype != null
                && (!runtimeTcpListenerPrototype.isFrozen()
                        || runtimeTcpListenerPrototype.parent().orElse(null)
                                != ProtosObjectValue.rootObject())) {
            throw new IllegalArgumentException(
                    "runtime TcpListener prototype must be a frozen direct child of Object");
        }

        if (runtimeTcpAcceptorPrototype != null
                && (!runtimeTcpAcceptorPrototype.isFrozen()
                        || runtimeTcpAcceptorPrototype.parent().orElse(null)
                                != ProtosObjectValue.rootObject())) {
            throw new IllegalArgumentException(
                    "runtime TcpAcceptor prototype must be a frozen direct child of Object");
        }

        if (!bindings.isFrozen()) {
            throw new IllegalArgumentException("prelude bindings must be frozen");
        }
        if (bindings.parent().orElse(null) != contextPrototype) {
            throw new IllegalArgumentException(
                    "prelude bindings must delegate to Context");
        }
        if (bindings.readLocalSlot("Context").orElse(null)
                != contextPrototype) {
            throw new IllegalArgumentException(
                    "prelude Context binding must be the Context prototype");
        }

        Object errorBinding = bindings.readLocalSlot("Error").orElse(null);
        if (!(errorBinding instanceof ProtosObjectValue errorPrototype)
                || errorPrototype.parent().orElse(null)
                        != ProtosObjectValue.rootObject()) {
            throw new IllegalArgumentException(
                    "prelude Error binding must be an ordinary child of Object");
        }
        this.errorPrototype = errorPrototype;
        // Frozen Prelude binding: resolve once, not during every Array creation.
        this.arrayPrototypeBinding = bindings.readLocalSlot("Array").orElse(null);
    }

    private static void requireFrozenDirectChildOfObject(
            ProtosObjectValue prototype, String name) {
        if (prototype != null
                && (!prototype.isFrozen()
                        || prototype.parent().orElse(null) != ProtosObjectValue.rootObject())) {
            throw new IllegalArgumentException(
                    "runtime " + name + " prototype must be a frozen direct child of Object");
        }
    }

    private static Map<ProtosModuleKey, Map<String, ProtosObjectValue>> copyStandardModuleMembers(
            Map<ProtosModuleKey, Map<String, ProtosObjectValue>> members) {
        Objects.requireNonNull(members, "standardModuleMembers");
        LinkedHashMap<ProtosModuleKey, Map<String, ProtosObjectValue>> copy =
                new LinkedHashMap<>();
        for (Map.Entry<ProtosModuleKey, Map<String, ProtosObjectValue>> entry :
                members.entrySet()) {
            ProtosModuleKey key = Objects.requireNonNull(entry.getKey(), "standard module key");
            LinkedHashMap<String, ProtosObjectValue> moduleMembers = new LinkedHashMap<>();
            for (Map.Entry<String, ProtosObjectValue> member :
                    Objects.requireNonNull(entry.getValue(), "standard module members")
                            .entrySet()) {
                String name = Objects.requireNonNull(member.getKey(), "standard member name");
                ProtosObjectValue value =
                        Objects.requireNonNull(member.getValue(), "standard member value");
                if (!value.isFrozen()) {
                    throw new IllegalArgumentException(
                            "standard module member must be frozen: " + key.canonicalId()
                                    + "." + name);
                }
                moduleMembers.put(name, value);
            }
            copy.put(key, Collections.unmodifiableMap(moduleMembers));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<ProtosModuleKey, Map<String, Supplier<ProtosObjectValue>>>
            copyLazyStandardModuleMembers(
                    Map<ProtosModuleKey, Map<String, Supplier<ProtosObjectValue>>> members,
                    Map<ProtosModuleKey, Map<String, ProtosObjectValue>> eagerMembers) {
        Objects.requireNonNull(members, "lazyStandardModuleMembers");
        LinkedHashMap<ProtosModuleKey, Map<String, Supplier<ProtosObjectValue>>> copy =
                new LinkedHashMap<>();
        for (Map.Entry<ProtosModuleKey, Map<String, Supplier<ProtosObjectValue>>> entry :
                members.entrySet()) {
            ProtosModuleKey key =
                    Objects.requireNonNull(entry.getKey(), "lazy standard module key");
            Map<String, ProtosObjectValue> eager = eagerMembers.getOrDefault(key, Map.of());
            LinkedHashMap<String, Supplier<ProtosObjectValue>> moduleMembers =
                    new LinkedHashMap<>();
            for (Map.Entry<String, Supplier<ProtosObjectValue>> member :
                    Objects.requireNonNull(entry.getValue(), "lazy standard module members")
                            .entrySet()) {
                String name =
                        Objects.requireNonNull(member.getKey(), "lazy standard member name");
                Supplier<ProtosObjectValue> supplier =
                        Objects.requireNonNull(
                                member.getValue(), "lazy standard member supplier");
                if (eager.containsKey(name)) {
                    throw new IllegalArgumentException(
                            "standard module member registered both eagerly and lazily: "
                                    + key.canonicalId() + "." + name);
                }
                moduleMembers.put(name, supplier);
            }
            copy.put(key, Collections.unmodifiableMap(moduleMembers));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<ProtosModuleKey, ProtosSemanticTransferFamily> copySemanticTransferFamilies(
            List<ProtosSemanticTransferFamily> families) {
        Objects.requireNonNull(families, "semanticTransferFamilies");
        LinkedHashMap<ProtosModuleKey, ProtosSemanticTransferFamily> byOwner = new LinkedHashMap<>();
        for (ProtosSemanticTransferFamily family : families) {
            Objects.requireNonNull(family, "semantic transfer family");
            if (byOwner.putIfAbsent(family.ownerModule(), family) != null) {
                throw new IllegalArgumentException(
                        "duplicate semantic transfer family: " + family.ownerModule().canonicalId());
            }
        }
        return Collections.unmodifiableMap(byOwner);
    }

    private static Set<ProtosObjectValue> identitiesOf(
            Map<ProtosModuleKey, Map<String, ProtosObjectValue>> members) {
        Set<ProtosObjectValue> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map<String, ProtosObjectValue> moduleMembers : members.values()) {
            identities.addAll(moduleMembers.values());
        }
        return Collections.unmodifiableSet(identities);
    }

    public ProtosObjectValue bindings() {
        return bindings;
    }

    public ProtosObjectValue contextPrototype() {
        return contextPrototype;
    }

    private ProtosObjectValue requiredOrdinaryBinding(String name) {
        Object binding = bindings.readLocalSlot(name).orElse(null);
        if (!(binding instanceof ProtosObjectValue object)) {
            throw new IllegalArgumentException(
                    "standard " + name + " binding must be an ordinary object");
        }
        return object;
    }

    public ProtosObjectValue numberPrototype() {
        return requiredOrdinaryBinding("Number");
    }

    public ProtosObjectValue integerPrototype() {
        return requiredOrdinaryBinding("Integer");
    }

    public ProtosObjectValue smallIntegerPrototype() {
        return requiredOrdinaryBinding("SmallInteger");
    }

    public ProtosObjectValue bigIntegerPrototype() {
        return requiredOrdinaryBinding("BigInteger");
    }

    /** D197-C: the canonical Fraction prototype; no Fraction value is minted here. */
    public ProtosObjectValue fractionPrototype() {
        return requiredOrdinaryBinding("Fraction");
    }

    public ProtosObjectValue floatPrototype() {
        return requiredOrdinaryBinding("Float");
    }

    /** D197-C (I090-C1): the canonical Complex prototype; no Complex value is minted here. */
    public ProtosObjectValue complexPrototype() {
        return requiredOrdinaryBinding("Complex");
    }


    public ProtosObjectValue errorPrototype() {
        return errorPrototype;
    }

    public ProtosObjectValue newError() {
        return new ProtosObjectValue(errorPrototype());
    }

    public ProtosObjectValue invalidReturnPrototype() {
        Object binding =
                bindings.readLocalSlot("InvalidReturn").orElseThrow();
        if (!(binding instanceof ProtosObjectValue invalidReturnPrototype)) {
            throw new IllegalStateException(
                    "standard InvalidReturn binding is not an ordinary object");
        }
        if (invalidReturnPrototype.parent().orElse(null) != errorPrototype()) {
            throw new IllegalStateException(
                    "standard InvalidReturn must delegate directly to Error");
        }
        return invalidReturnPrototype;
    }

    public ProtosObjectValue newInvalidReturn() {
        return new ProtosObjectValue(invalidReturnPrototype());
    }

    ProtosObjectValue standardErrorPrototype(String name) {
        Objects.requireNonNull(name, "name");
        Optional<Object> found = bindings.readLocalSlot(name);
        if (found.isEmpty()) {
            // TEST009-AJ: a missing named standard Error binding is a real but cold
            // failure; keep its NoSuchElementException out of partial evaluation.
            CompilerDirectives.transferToInterpreter();
            throw new NoSuchElementException("No value present");
        }
        Object binding = found.orElse(null);
        if (!(binding instanceof ProtosObjectValue prototype)) {
            throw new IllegalStateException(
                    "standard " + name + " binding is not an ordinary object");
        }
        ProtosObjectValue current = prototype;
        while (true) {
            if (current == errorPrototype()) return prototype;
            Object parent = current.parent().orElse(null);
            if (!(parent instanceof ProtosObjectValue parentObject)) {
                throw new IllegalStateException(
                        "standard " + name + " is outside the Error hierarchy");
            }
            current = parentObject;
        }
    }

    public ProtosObjectValue stringPrototype() {
        return requiredOrdinaryBinding("String");
    }

    public ProtosObjectValue encodingPrototype() {
        return requiredOrdinaryBinding("Encoding");
    }

    public ProtosObjectValue identityMapPrototype() {
        return requiredOrdinaryBinding("IdentityMap");
    }

    public ProtosObjectValue pathPrototype() { return requiredOrdinaryBinding("Path"); }

    public ProtosObjectValue futurePrototype() { return requiredOrdinaryBinding("Future"); }

    public ProtosObjectValue processPrototype() { return requiredOrdinaryBinding("Process"); }

    public ProtosObjectValue networkPrototype() { return requiredOrdinaryBinding("Network"); }

    public ProtosObjectValue textWriterPrototype() {
        return requiredOrdinaryBinding("TextWriter");
    }

    /**
     * Binds, exactly once during bootstrap, the module runtime installed behind this Prelude's
     * source-backed import facility. Not part of the public Prelude meaning.
     */
    public synchronized void bindModuleRuntimeForRuntime(Object runtime) {
        Objects.requireNonNull(runtime, "runtime");
        if (moduleRuntime != null) {
            throw new IllegalStateException("module runtime is already bound");
        }
        moduleRuntime = runtime;
    }

    /** The bound module runtime of this Prelude's import facility, or {@code null}. */
    public synchronized Object moduleRuntimeForRuntime() {
        return moduleRuntime;
    }

    /** Runtime-only exact source-backed Bytes prototype omitted from public prelude bindings. */
    public ProtosObjectValue bytesPrototypeForRuntime() {
        if (runtimeBytesPrototype == null) {
            throw new IllegalStateException(
                    "this prelude does not retain the standard runtime Bytes prototype");
        }
        return runtimeBytesPrototype;
    }

    /** Private authority-free Clock protocol; reduced legacy fixtures cannot provision grants. */
    public ProtosObjectValue clockPrototypeForRuntime() {
        if (runtimeClockPrototype == null) {
            throw new IllegalStateException("this prelude does not retain the runtime Clock prototype");
        }
        return runtimeClockPrototype;
    }

    /**
     * D243-C private Entropy protocol identity.
     *
     * <p>Compatibility and test Preludes without this prototype remain
     * usable but cannot provision an Entropy grant.
     */
    public ProtosObjectValue entropyPrototypeForRuntime() {
        if (runtimeEntropyPrototype == null) {
            throw new IllegalStateException(
                    "this prelude does not retain the runtime Entropy prototype");
        }
        return runtimeEntropyPrototype;
    }

    /** Nullable-safe identity test used only by isolation transfer machinery. */
    public boolean isBytesPrototypeForRuntime(Object candidate) {
        return runtimeBytesPrototype != null && candidate == runtimeBytesPrototype;
    }

    /** Runtime-only exact source-backed ActorRef prototype omitted from public prelude bindings. */
    public ProtosObjectValue actorRefPrototypeForRuntime() {
        if (runtimeActorRefPrototype == null) {
            throw new IllegalStateException(
                    "this prelude does not retain the standard runtime ActorRef prototype");
        }
        return runtimeActorRefPrototype;
    }

    /** Runtime-only exact TcpConnection protocol prototype omitted from public Prelude bindings. */
    public ProtosObjectValue tcpConnectionPrototypeForRuntime() {
        if (runtimeTcpConnectionPrototype == null) {
            throw new IllegalStateException(
                    "this prelude does not retain the standard runtime TcpConnection prototype");
        }
        return runtimeTcpConnectionPrototype;
    }

    /** Nullable-safe identity test used only by isolation transfer machinery. */
    public boolean isTcpConnectionPrototypeForRuntime(Object candidate) {
        return runtimeTcpConnectionPrototype != null && candidate == runtimeTcpConnectionPrototype;
    }

    /** Runtime-only exact TcpListener protocol prototype omitted from public Prelude bindings. */
    public ProtosObjectValue tcpListenerPrototypeForRuntime() {
        if (runtimeTcpListenerPrototype == null) {
            throw new IllegalStateException(
                    "this prelude does not retain the standard runtime TcpListener prototype");
        }
        return runtimeTcpListenerPrototype;
    }

    /** Nullable-safe identity test used only by isolation transfer machinery. */
    public boolean isTcpListenerPrototypeForRuntime(Object candidate) {
        return runtimeTcpListenerPrototype != null && candidate == runtimeTcpListenerPrototype;
    }

    /**
     * Runtime-only source-backed Filesystem open method (D241-B) omitted from public Prelude
     * bindings. Each host-provisioned Filesystem binds it as its own {@code open} slot.
     */
    public ProtosClosureValue filesystemOpenMethodForRuntime() {
        if (runtimeFilesystemOpenMethod == null) {
            throw new IllegalStateException(
                    "this prelude does not retain the source-backed Filesystem open method");
        }
        return runtimeFilesystemOpenMethod;
    }

    /** Runtime-only exact TcpAcceptor protocol prototype omitted from public Prelude bindings. */
    public ProtosObjectValue tcpAcceptorPrototypeForRuntime() {
        if (runtimeTcpAcceptorPrototype == null) {
            throw new IllegalStateException(
                    "this prelude does not retain the standard runtime TcpAcceptor prototype");
        }
        return runtimeTcpAcceptorPrototype;
    }

    /** Nullable-safe identity test used only by isolation transfer machinery. */
    public boolean isTcpAcceptorPrototypeForRuntime(Object candidate) {
        return runtimeTcpAcceptorPrototype != null && candidate == runtimeTcpAcceptorPrototype;
    }

    /** The D223-A1 module key whose two standard members are the Resolver family prototypes. */
    public static final ProtosModuleKey RESOLVERS_MODULE_KEY =
            ProtosModuleKey.module("std:network/resolvers");

    /**
     * Runtime-only canonical source-backed Resolver protocol prototype (D223-A1), retained as a
     * standard member of {@code std:network/resolvers} and omitted from public Prelude bindings.
     */
    public ProtosObjectValue resolverPrototypeForRuntime() {
        return resolversMember("Resolver");
    }

    /** Runtime-only canonical source-defined ResolutionError prototype (D223-A1). */
    public ProtosObjectValue resolutionErrorPrototypeForRuntime() {
        return resolversMember("ResolutionError");
    }

    private ProtosObjectValue resolversMember(String name) {
        Map<String, ProtosObjectValue> members = standardModuleMembers.get(RESOLVERS_MODULE_KEY);
        ProtosObjectValue member = members == null ? null : members.get(name);
        if (member == null) {
            throw new IllegalStateException(
                    "this prelude does not retain the standard runtime " + name + " prototype");
        }
        return member;
    }

    /** Runtime-only canonical IpAddress family omitted from public Prelude bindings. */
    public ProtosObjectValue ipAddressPrototypeForRuntime() {
        if (runtimeIpAddressPrototype == null) {
            throw new IllegalStateException(
                    "this prelude does not retain the standard runtime IpAddress prototype");
        }
        return runtimeIpAddressPrototype;
    }

    /** Runtime-only canonical IpEndpoint family omitted from public Prelude bindings. */
    public ProtosObjectValue ipEndpointPrototypeForRuntime() {
        if (runtimeIpEndpointPrototype == null) {
            throw new IllegalStateException(
                    "this prelude does not retain the standard runtime IpEndpoint prototype");
        }
        return runtimeIpEndpointPrototype;
    }

    /**
     * Exact-identity test used only by isolation transfer machinery.
     *
     * <p>Every registered standard-module member is a FROZEN runtime-owned standard object shared
     * by all Actor and P executions of this Prelude, so transfer keeps it as an exact anchor
     * instead of copying it or importing its module to recover it. The test consults only the
     * registration made at construction: it performs no import, module initialization, cache
     * mutation, source execution, or resolver request.
     */
    public boolean isStandardModuleMemberForRuntime(Object candidate) {
        return candidate instanceof ProtosObjectValue object
                && standardModuleMemberIdentities.contains(object);
    }

    /**
     * True only when this Prelude's bootstrap authorized this exact family descriptor (PLAT051).
     * Consulted only for values that already carry a family; it performs no import, module
     * initialization, source execution or mutation.
     */
    public boolean authorizesSemanticTransferFamilyForRuntime(ProtosSemanticTransferFamily family) {
        return family != null && semanticTransferFamilies.get(family.ownerModule()) == family;
    }

    /**
     * Installs the immutable standard initial members registered for {@code moduleKey} into a
     * fresh Actor-local module context, before that context is cached or its source executes.
     *
     * <p>This is the only standard-member provisioning path for module creation; it neither
     * imports, caches, nor creates module identity. A {@code null} key (non-module activations)
     * and unregistered keys install nothing.
     *
     * <p>After the shared eager members, each deferred member registered for the exact key is
     * created by invoking its supplier once for this module context; the answer must be a FROZEN
     * object and becomes a private initial member of this module instance only (LIB022-A).
     */
    public void installStandardModuleMembersForRuntime(
            ProtosModuleKey moduleKey, ProtosObjectValue moduleContext) {
        Objects.requireNonNull(moduleContext, "moduleContext");
        if (moduleKey == null) {
            return;
        }
        Map<String, ProtosObjectValue> members = standardModuleMembers.get(moduleKey);
        if (members != null) {
            for (Map.Entry<String, ProtosObjectValue> member : members.entrySet()) {
                if (moduleContext.hasLocalSlot(member.getKey())) {
                    throw new IllegalStateException(
                            "duplicate standard module member: " + moduleKey.canonicalId()
                                    + "." + member.getKey());
                }
                moduleContext.createLocalSlot(member.getKey(), member.getValue());
            }
        }
        Map<String, Supplier<ProtosObjectValue>> deferred =
                lazyStandardModuleMembers.get(moduleKey);
        if (deferred == null) {
            return;
        }
        for (Map.Entry<String, Supplier<ProtosObjectValue>> member : deferred.entrySet()) {
            ProtosObjectValue value = member.getValue().get();
            if (value == null) {
                throw new IllegalStateException(
                        "lazy standard module member supplied null: " + moduleKey.canonicalId()
                                + "." + member.getKey());
            }
            if (!value.isFrozen()) {
                throw new IllegalStateException(
                        "lazy standard module member must be frozen: " + moduleKey.canonicalId()
                                + "." + member.getKey());
            }
            if (moduleContext.hasLocalSlot(member.getKey())) {
                throw new IllegalStateException(
                        "duplicate standard module member: " + moduleKey.canonicalId()
                                + "." + member.getKey());
            }
            moduleContext.createLocalSlot(member.getKey(), value);
        }
    }

    public ProtosObjectValue arrayPrototype() {
        Object binding = arrayPrototypeBinding;
        if (binding == null) {
            // TEST009-AI: a Prelude whose frozen bindings lack Array is a real but cold
            // failure; keep its NoSuchElementException out of partial evaluation.
            CompilerDirectives.transferToInterpreter();
            throw new NoSuchElementException("No value present");
        }
        if (!(binding instanceof ProtosObjectValue arrayPrototype)) {
            throw new IllegalStateException(
                    "standard Array binding is not an ordinary object");
        }
        return arrayPrototype;
    }

    public ProtosObjectValue mapPrototype() { return requiredOrdinaryBinding("Map"); }
    public ProtosMapValue newMap() { return new ProtosMapValue(mapPrototype()); }

    public ProtosArrayValue newArray(java.util.List<?> elements) {
        return new ProtosArrayValue(arrayPrototype(), elements);
    }

    public ProtosArrayValue newFrozenArray(java.util.List<?> elements) {
        ProtosArrayValue array = newArray(elements);
        array.freeze();
        return array;
    }

    /**
     * D242: allocates the ordinary public object of one MODULE. It delegates to the canonical
     * {@code Object} prototype exactly like an ordinary object literal and carries no export
     * metadata.
     */
    public ProtosObjectValue newPublicModuleObject() {
        return new ProtosObjectValue(requiredOrdinaryBinding("Object"));
    }

    public ProtosObjectValue newExecutionContext() {
        return new ProtosExecutionContextValue(contextPrototype);
    }

    /**
     * Runtime-only direct construction for a deferred execution Context whose
     * definitive lexical authority already exists (I115-S1c-L: the
     * frame-backed authority of a genuine Closure activation). The Context
     * has the standard Context prototype as its delegation parent, exactly
     * like {@link #newExecutionContext()}; no guest-visible constructor or
     * object category is introduced.
     */
    ProtosExecutionContextValue newExecutionContextForRuntime(
            ProtosLexicalBindingAuthority lexicalBindingAuthority) {
        return new ProtosExecutionContextValue(
                contextPrototype,
                Objects.requireNonNull(
                        lexicalBindingAuthority,
                        "lexicalBindingAuthority"));
    }

    public ProtosActivation newModuleActivation() {
        ProtosObjectValue moduleContext = newExecutionContext();
        return newModuleActivation(new ProtosActorModuleState(), null, moduleContext, new ProtosActorExecutionDomain());
    }

    public ProtosActivation newModuleActivation(
            ProtosActorModuleState actorModuleState,
            ProtosModuleKey moduleKey,
            ProtosObjectValue moduleContext) {
        return newModuleActivation(actorModuleState, moduleKey, moduleContext, new ProtosActorExecutionDomain());
    }

    public ProtosActivation newModuleActivation(
            ProtosActorModuleState actorModuleState,
            ProtosModuleKey moduleKey,
            ProtosObjectValue moduleContext,
            ProtosActorExecutionDomain executionDomain) {
        return ProtosActivation.withPreludeAndModuleState(
                Objects.requireNonNull(moduleContext, "moduleContext"),
                java.util.List.of(bindings),
                moduleContext,
                this,
                Objects.requireNonNull(actorModuleState, "actorModuleState"),
                moduleKey,
                Objects.requireNonNull(executionDomain, "executionDomain"));
    }

    /**
     * D242 MODULE initializer activation. The initializer executes in {@code privateContext};
     * lexical lookup continues through {@code publicModule} and then the frozen Prelude, and the
     * receiver {@code this} is the public module object. Publication is ordinary slot creation on
     * that receiver ({@code this.Name: value}); nothing is copied at completion.
     */
    public ProtosActivation newModuleInitializerActivation(
            ProtosActorModuleState actorModuleState,
            ProtosModuleKey moduleKey,
            ProtosObjectValue privateContext,
            ProtosObjectValue publicModule,
            ProtosActorExecutionDomain executionDomain) {
        Objects.requireNonNull(privateContext, "privateContext");
        Objects.requireNonNull(publicModule, "publicModule");
        if (privateContext == publicModule) {
            throw new IllegalArgumentException(
                    "MODULE private context must differ from its public object");
        }
        return ProtosActivation.withPreludeAndModuleState(
                privateContext,
                java.util.List.of(publicModule, bindings),
                publicModule,
                this,
                Objects.requireNonNull(actorModuleState, "actorModuleState"),
                Objects.requireNonNull(moduleKey, "moduleKey"),
                Objects.requireNonNull(executionDomain, "executionDomain"));
    }
}
