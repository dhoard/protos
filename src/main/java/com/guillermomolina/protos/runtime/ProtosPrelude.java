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

public final class ProtosPrelude {
    private final ProtosObjectValue bindings;
    private final ProtosObjectValue contextPrototype;
    private final ProtosObjectValue errorPrototype;
    private final ProtosObjectValue runtimeBytesPrototype;
    private final ProtosObjectValue runtimeActorRefPrototype;
    private final ProtosObjectValue runtimeTcpConnectionPrototype;
    private final ProtosObjectValue runtimeTcpListenerPrototype;
    private final ProtosObjectValue runtimeIpAddressPrototype;
    private final ProtosObjectValue runtimeIpEndpointPrototype;
    private final Map<ProtosModuleKey, Map<String, ProtosObjectValue>> standardModuleMembers;
    private final Set<ProtosObjectValue> standardModuleMemberIdentities;
    private final Map<ProtosModuleKey, ProtosSemanticTransferFamily> semanticTransferFamilies;

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
        this.semanticTransferFamilies = copySemanticTransferFamilies(semanticTransferFamilies);
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.contextPrototype =
                Objects.requireNonNull(contextPrototype, "contextPrototype");
        this.runtimeBytesPrototype = runtimeBytesPrototype;
        this.runtimeActorRefPrototype = runtimeActorRefPrototype;
        this.runtimeTcpConnectionPrototype = runtimeTcpConnectionPrototype;
        this.runtimeTcpListenerPrototype = runtimeTcpListenerPrototype;
        this.runtimeIpAddressPrototype = runtimeIpAddressPrototype;
        this.runtimeIpEndpointPrototype = runtimeIpEndpointPrototype;
        this.standardModuleMembers = copyStandardModuleMembers(standardModuleMembers);
        this.standardModuleMemberIdentities = identitiesOf(this.standardModuleMembers);

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

    /** Runtime-only exact source-backed Bytes prototype omitted from public prelude bindings. */
    public ProtosObjectValue bytesPrototypeForRuntime() {
        if (runtimeBytesPrototype == null) {
            throw new IllegalStateException(
                    "this prelude does not retain the standard runtime Bytes prototype");
        }
        return runtimeBytesPrototype;
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
     */
    public void installStandardModuleMembersForRuntime(
            ProtosModuleKey moduleKey, ProtosObjectValue moduleContext) {
        Objects.requireNonNull(moduleContext, "moduleContext");
        if (moduleKey == null) {
            return;
        }
        Map<String, ProtosObjectValue> members = standardModuleMembers.get(moduleKey);
        if (members == null) {
            return;
        }
        for (Map.Entry<String, ProtosObjectValue> member : members.entrySet()) {
            if (moduleContext.hasLocalSlot(member.getKey())) {
                throw new IllegalStateException(
                        "duplicate standard module member: " + moduleKey.canonicalId()
                                + "." + member.getKey());
            }
            moduleContext.createLocalSlot(member.getKey(), member.getValue());
        }
    }

    public ProtosObjectValue arrayPrototype() {
        Optional<Object> found = bindings.readLocalSlot("Array");
        if (found.isEmpty()) {
            // TEST009-AI: a Prelude whose frozen bindings lack Array is a real but cold
            // failure; keep its NoSuchElementException out of partial evaluation.
            CompilerDirectives.transferToInterpreter();
            throw new NoSuchElementException("No value present");
        }
        Object binding = found.orElse(null);
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

    public ProtosObjectValue newExecutionContext() {
        return new ProtosExecutionContextValue(contextPrototype);
    }

    /**
     * Runtime-only direct construction for a deferred execution Context whose
     * definitive lexical authority already exists.
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
}
