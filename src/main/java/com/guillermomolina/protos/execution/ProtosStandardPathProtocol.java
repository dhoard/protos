/* APL-1.0 licensed work; see LICENSE.TXT. */
package com.guillermomolina.protos.execution;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPathValue;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Bootstrap bridge for the source-backed Core Path prototype (D169).
 *
 * <p>{@code relative}, {@code child}, {@code ==} and {@code hash} are source closures of
 * {@code lib/core/Path.protos}. The runtime only fills the private {@code _corePathKernel} with
 * three physical primitives over the immutable {@link ProtosPathValue} carrier: {@code mint}
 * builds a carrier, {@code componentCount} and {@code componentAt} read it. None of them
 * dispatches guest behavior.
 */
public final class ProtosStandardPathProtocol {
    private static final Set<String> SOURCE_SELECTORS =
            Set.of("relative", "child", "_corePathEquals", "hash");

    private ProtosStandardPathProtocol() {}

    public static void install(ProtosObjectValue prototype, ProtosObjectValue kernel) {
        Objects.requireNonNull(prototype, "prototype");
        Objects.requireNonNull(kernel, "kernel");
        if (prototype.parent().orElse(null) != ProtosObjectValue.rootObject()) {
            throw new IllegalStateException(
                    "standard Path prototype must delegate directly to Object");
        }
        if (!prototype.isOpen()
                || !prototype.localSlotsSnapshot().keySet().equals(SOURCE_SELECTORS)) {
            throw new IllegalStateException(
                    "standard Path prototype must be an open source object with exactly "
                            + "relative, child, _corePathEquals and hash");
        }
        for (String selector : SOURCE_SELECTORS) {
            Object value = prototype.readLocalSlot(selector).orElseThrow();
            if (!(value instanceof ProtosClosureValue closure)
                    || closure.definition() == null
                    || closure.executionPlan().isEmpty()
                    || closure.nativeBody().isPresent()) {
                throw new IllegalStateException(
                        "standard Path " + selector + " must be a source-backed Closure");
            }
        }
        // A source slot target cannot spell an operator; the same Closure moves to ==.
        Object equality = prototype.readLocalSlot("_corePathEquals").orElseThrow();
        prototype.removeLocalSlot("_corePathEquals");
        prototype.createLocalSlot("==", equality);

        if (!kernel.isOpen()
                || kernel.parent().orElse(null) != ProtosObjectValue.rootObject()
                || !kernel.localSlotsSnapshot().isEmpty()) {
            throw new IllegalStateException(
                    "Core _corePathKernel must be an empty open ordinary object");
        }
        kernel.createLocalSlot(
                "mint",
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> mint(activation, supplied, kernel, prototype)));
        kernel.createLocalSlot(
                "componentCount",
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> {
                            requireKernel(activation, supplied, kernel, 1);
                            return supplied.get(0) instanceof ProtosPathValue path
                                    ? ProtosNumericValueSupport.integer(path.components().size())
                                    : ProtosNumericValueSupport.integer(-1);
                        }));
        kernel.createLocalSlot(
                "componentAt",
                ProtosClosureValue.nativeClosure(
                        (activation, supplied) -> {
                            requireKernel(activation, supplied, kernel, 2);
                            if (!(supplied.get(0) instanceof ProtosPathValue path)
                                    || !ProtosNumericValueSupport.isIntegerInIntRange(
                                            supplied.get(1))) {
                                throw invalid(activation);
                            }
                            int index = ProtosNumericValueSupport.exactInt(supplied.get(1));
                            if (index < 0 || index >= path.components().size()) {
                                throw invalid(activation);
                            }
                            return new ProtosStringValue(path.components().get(index));
                        }));
        kernel.freeze();
    }

    /*
     * mint(factory) builds the empty carrier for any object delegating to the canonical
     * prototype; mint(path, name) appends one physically valid opaque component. Both always
     * use the canonical prototype. Semantic validation of child belongs to the source.
     */
    private static Object mint(
            ProtosActivation activation,
            List<?> supplied,
            ProtosObjectValue kernel,
            ProtosObjectValue prototype) {
        if (activation.receiver() != kernel) {
            throw invalid(activation);
        }
        if (supplied.size() == 1) {
            if (!(supplied.get(0) instanceof ProtosObjectValue factory)
                    || !delegatesTo(factory, prototype)) {
                throw invalid(activation);
            }
            return new ProtosPathValue(prototype, List.of());
        }
        if (supplied.size() == 2
                && supplied.get(0) instanceof ProtosPathValue path
                && supplied.get(1) instanceof ProtosStringValue name) {
            String component = name.value();
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw invalid(activation);
            }
            ArrayList<String> extended = new ArrayList<>(path.components());
            extended.add(component);
            return new ProtosPathValue(prototype, extended);
        }
        throw invalid(activation);
    }

    private static void requireKernel(
            ProtosActivation activation, List<?> supplied, ProtosObjectValue kernel, int arity) {
        if (activation.receiver() != kernel || supplied.size() != arity) {
            throw invalid(activation);
        }
    }

    private static boolean delegatesTo(ProtosObjectValue receiver, ProtosObjectValue prototype) {
        Object current = receiver;
        while (current instanceof ProtosObjectValue object) {
            if (object == prototype) {
                return true;
            }
            current = object.parent().orElse(null);
        }
        return false;
    }

    private static ProtosSignalException invalid(ProtosActivation activation) {
        return new ProtosSignalException(ProtosCoreErrors.newError(activation));
    }
}
