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

import com.guillermomolina.protos.runtime.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/**
 * Forms a detached, authority-free observation snapshot of a completed Protos value.
 *
 * <p>This is deliberately stricter than Actor transfer. It never rematerializes Process,
 * ActorRef, GroupRef, stream, Filesystem or other authority and never carries executable state
 * such as Closure, Future, task, activation or byte-region custody across an execution boundary.
 * The initial supported data families are ordinary scalar values plus authority-free ordinary
 * Object/Array/Bytes graphs whose complete reachable graph satisfies the same restriction.
 *
 * <p>When source and destination use the same Core Prelude, frozen standard prelude objects may be
 * shared because they carry no instance authority. When the Preludes differ, the explicit
 * cross-Prelude entry point rematerializes the closed standard Error taxonomy to the corresponding
 * destination prototypes before ordinary detached graph copying proceeds. It never shares a
 * source-Prelude Error prototype into the destination.
 */
public final class ProtosDetachedExecutionValue {
    private ProtosDetachedExecutionValue() {}

    public static Object snapshot(Object value, ProtosActivation destination) {
        ProtosPrelude destinationPrelude =
                Objects.requireNonNull(destination, "destination")
                        .prelude()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "detached execution snapshot requires Core prelude"));
        return new Copier(destinationPrelude, destination).copy(value);
    }

    /**
     * Forms a detached snapshot when source and destination executions use distinct Core Preludes.
     *
     * <p>The source Prelude is descriptive only: it identifies standard Error taxonomy objects that
     * must be rematerialized to the corresponding destination taxonomy. It grants no authority and
     * is never exposed to the destination value graph.
     */
    public static Object snapshot(
            Object value,
            ProtosPrelude sourcePrelude,
            ProtosActivation destination) {
        return new Copier(sourcePrelude, destination).copy(value);
    }

    private static final class Copier {
        private final ProtosActivation destination;
        private final ProtosPrelude sourcePrelude;
        private final ProtosPrelude prelude;
        private final IdentityHashMap<Object, Object> memo = new IdentityHashMap<>();
        private final Set<Object> populating =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Object> populated =
                Collections.newSetFromMap(new IdentityHashMap<>());

        private Copier(
                ProtosPrelude sourcePrelude,
                ProtosActivation destination) {
            this.destination = Objects.requireNonNull(destination, "destination");
            this.sourcePrelude = Objects.requireNonNull(sourcePrelude, "sourcePrelude");
            this.prelude =
                    destination
                            .prelude()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "detached execution snapshot requires Core prelude"));
        }

        private Object copy(Object value) {
            Object result = allocate(value);
            populate(value);
            return result;
        }

        private Object allocate(Object value) {
            if (value == null) {
                throw nonTransferable();
            }
            if (memo.containsKey(value)) {
                return memo.get(value);
            }

            if (value == ProtosNullValue.INSTANCE
                    || value == ProtosBooleanValue.TRUE
                    || value == ProtosBooleanValue.FALSE) {
                return remember(value, value);
            }
            // I091: Core Fraction and Complex are authority-free exact values.
            // Reuse their inert transfer descriptors so no source Prelude,
            // prototype, or sealed family state crosses the detached boundary.
            if (ProtosNumericValueSupport.isFraction(value)) {
                return remember(
                        value,
                        ProtosNumericValueSupport.materializeFractionTransferDescriptor(
                                ProtosNumericValueSupport.fractionTransferDescriptor(value),
                                prelude));
            }
            if (ProtosComplexNumericSupport.isComplex(value)) {
                return remember(
                        value,
                        ProtosComplexNumericSupport.materializeTransferDescriptor(
                                ProtosComplexNumericSupport.transferDescriptor(value),
                                prelude));
            }
            Object copiedNumber =
                    ProtosNumericValueSupport.copyCurrentNumberOrNull(value, prelude);
            if (copiedNumber != null) {
                return remember(value, copiedNumber);
            }
            if (value instanceof ProtosStringValue string) {
                return remember(value, string.copyForRuntime());
            }

            if (nonTransferableRuntimeValue(value)) {
                throw nonTransferable();
            }

            if (!(value instanceof ProtosObjectValue object)) {
                throw nonTransferable();
            }

            ProtosObjectValue remappedStandardError =
                    remappedStandardErrorPrototype(object);
            if (remappedStandardError != null) {
                return remember(value, remappedStandardError);
            }

            if (isSharedStandardObject(object)) {
                return remember(value, value);
            }
            if (object.parent().orElse(null) == prelude.contextPrototype()) {
                throw nonTransferable();
            }

            Object sourceParent =
                    object.parent()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "non-root observed object lost parent"));
            Object destinationParent = allocate(sourceParent);

            Object shell;
            if (object instanceof ProtosArrayValue array) {
                int size = array.indexedSize();
                ArrayList<Object> placeholders = new ArrayList<>(size);
                for (int index = 0; index < size; index++) {
                    placeholders.add(ProtosNullValue.INSTANCE);
                }
                shell = new ProtosArrayValue(destinationParent, placeholders);
            } else if (object instanceof ProtosBytesValue) {
                shell = new ProtosBytesValue(destinationParent);
            } else if (object.getClass() == ProtosObjectValue.class) {
                shell = new ProtosObjectValue(destinationParent);
            } else {
                throw nonTransferable();
            }
            memo.put(value, shell);
            return shell;
        }

        private void populate(Object value) {
            if (value == null || populated.contains(value)) {
                return;
            }
            if (!(value instanceof ProtosObjectValue source)
                    || ProtosNumericValueSupport.isCurrentNumber(value)
                    || isSharedStandardObject(source)
                    || nonTransferableRuntimeValue(value)) {
                populated.add(value);
                return;
            }
            if (!populating.add(value)) {
                return;
            }

            try {
                Object parent = source.parent().orElse(null);
                if (parent != null) {
                    populate(parent);
                }

                ProtosObjectValue destinationObject =
                        (ProtosObjectValue) memo.get(value);

                if (source instanceof ProtosArrayValue sourceArray) {
                    ProtosArrayValue destinationArray =
                            (ProtosArrayValue) destinationObject;
                    java.util.List<Object> elements =
                            sourceArray.indexedSnapshot();
                    for (int index = 0; index < elements.size(); index++) {
                        destinationArray.indexedPut(
                                index,
                                copy(elements.get(index)));
                    }
                } else if (source instanceof ProtosBytesValue sourceBytes) {
                    ProtosBytesValue destinationBytes =
                            (ProtosBytesValue) destinationObject;
                    for (Object octet : sourceBytes.indexedSnapshot()) {
                        destinationBytes.indexedAdd(copy(octet));
                    }
                }

                java.util.ArrayList<String> slotNames = new java.util.ArrayList<>();
                java.util.ArrayList<Object> slotValues = new java.util.ArrayList<>();
                source.appendLocalBindingsTo(slotNames, slotValues);
                for (int index = 0; index < slotNames.size(); index++) {
                    destinationObject.createLocalSlot(
                            slotNames.get(index),
                            copy(slotValues.get(index)));
                }

                if (source.isFrozen()) {
                    destinationObject.freeze();
                } else if (source.isClosed()) {
                    destinationObject.close();
                }
                populated.add(value);
            } finally {
                populating.remove(value);
            }
        }

        private ProtosObjectValue remappedStandardErrorPrototype(
                ProtosObjectValue object) {
            for (ProtosCoreErrors.StandardError standardError :
                    ProtosCoreErrors.StandardError.values()) {
                Object sourcePrototype =
                        sourcePrelude
                                .bindings()
                                .readLocalSlot(standardError.prototypeName())
                                .orElse(null);
                if (sourcePrototype != object) {
                    continue;
                }

                Object destinationPrototype =
                        prelude
                                .bindings()
                                .readLocalSlot(standardError.prototypeName())
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "destination Core Prelude is missing standard "
                                                                + standardError.prototypeName()));
                if (!(destinationPrototype instanceof ProtosObjectValue destinationObject)) {
                    throw new IllegalStateException(
                            "destination standard "
                                    + standardError.prototypeName()
                                    + " binding is not an ordinary object");
                }
                return destinationObject;
            }
            return null;
        }

        private boolean isSharedStandardObject(ProtosObjectValue object) {
            if (object == ProtosObjectValue.rootObject()
                    || object == prelude.bindings()
                    || object == prelude.contextPrototype()
                    || prelude.isStandardModuleMemberForRuntime(object)) {
                return true;
            }
            java.util.ArrayList<String> bindingNames = new java.util.ArrayList<>();
            java.util.ArrayList<Object> bindingValues = new java.util.ArrayList<>();
            prelude.bindings().appendLocalBindingsTo(bindingNames, bindingValues);
            for (int index = 0; index < bindingValues.size(); index++) {
                if (bindingValues.get(index) == object) {
                    return true;
                }
            }
            return false;
        }

        private static boolean nonTransferableRuntimeValue(Object value) {
            return value instanceof ProtosClosureValue
                    || value instanceof ProtosFutureValue
                    || value instanceof ProtosTask
                    || value instanceof ProtosActivation
                    || value instanceof ProtosFileValue
                    || value instanceof ProtosFilesystemValue
                    || value instanceof ProtosProcessCapabilityValue
                    || value instanceof ProtosEnvironmentValue
                    || value instanceof ProtosProcessStandardStreamValue
                    || value instanceof ProtosActorRefValue
                    || value instanceof ProtosGroupRefValue
                    || value instanceof ProtosSendOperationControl;
        }

        private Object remember(Object source, Object destinationValue) {
            memo.put(source, destinationValue);
            populated.add(source);
            return destinationValue;
        }

        private ProtosSignalException nonTransferable() {
            return new ProtosSignalException(
                    ProtosCoreErrors.newOccurrence(
                            destination,
                            ProtosCoreErrors.StandardError.NON_TRANSFERABLE_VALUE));
        }
    }
}
