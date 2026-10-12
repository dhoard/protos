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

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Restricted host-Java provider of the {@code java:} import scheme (I082-G).
 *
 * <p>The backend is the host JVM itself: no Espresso, guest JVM, Polyglot Context, class loader,
 * or dependency acquisition is involved. The provider reaches exactly the surface of its immutable
 * {@link ProtosHostJavaCatalogue}; target canonicalization is a pure catalogue lookup, so a denied
 * class is never loaded, initialized, or probed. Its execution profile is {@code
 * RESTRICTED_IN_PROCESS} and its PLAT052 mechanism is the catalogue confinement, with no
 * provisioned authority.
 *
 * <p>Everything else is the shared D188/D189 substrate. An imported class is the Actor-local
 * facade over its catalogue entry: the facade deliberately publishes its {@code call} slot as the
 * exposed constructor (D188 "Callability and construction"), and
 * reading an exposed static method yields an executable bound to it. An admitted Java object is a
 * raw reference whose stable identity is host reference identity, never Java {@code equals}; reading
 * an exposed instance method yields an executable bound to that exact receiver. Member invocation is
 * therefore the ordinary read followed by ordinary {@code call}. Reflection is used only to invoke
 * those preselected public members and is never reachable as a value.
 *
 * <p>The compartment and sessions are lightweight: they hold no Java application state, and every
 * admitted Java object stays bound to the session generation that produced it. This baseline
 * accepts no D189 callback argument: a Closure passed to a Java member fails before entry.
 */
final class ProtosHostJavaProvider
        implements ProtosForeignModuleProvider,
                ProtosForeignProviderFactory,
                ProtosForeignValueAdapter {
    static final String SCHEME = "java";
    static final ProtosForeignProviderId ID = new ProtosForeignProviderId("host-java");
    private static final String LANGUAGE = "java";
    /** Conversion marker distinct from every Java argument value, including null. */
    private static final Object REJECTED = new Object();

    private final ProtosHostJavaCatalogue catalogue;

    private ProtosHostJavaProvider(ProtosHostJavaCatalogue catalogue) {
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
    }

    /** The immutable host descriptor of a provider over exactly {@code catalogue}. */
    static ProtosForeignProviderDescriptor descriptor(ProtosHostJavaCatalogue catalogue) {
        ProtosHostJavaProvider provider = new ProtosHostJavaProvider(catalogue);
        return new ProtosForeignProviderDescriptor(
                ID,
                ProtosForeignProviderExecutionProfile.RESTRICTED_IN_PROCESS,
                Optional.of(new CatalogueConfinement()),
                provider,
                Optional.of(new ProtosForeignImportRoute(SCHEME, provider)),
                provider);
    }

    // ---- module routing

    /** Exact binary class name of an admitted class; never loads or names an unadmitted one. */
    @Override
    public String canonicalTarget(String exactTarget) {
        if (catalogue.lookup(exactTarget).isEmpty()) {
            throw new IllegalArgumentException("Java class is not admitted for import");
        }
        return exactTarget;
    }

    @Override
    public Object acquireTarget(ProtosForeignProviderSession session, String canonicalTarget) {
        return catalogue.lookup(canonicalTarget)
                .orElseThrow(() -> new IllegalStateException("Java class is not admitted"));
    }

    /** The facade of a class with an exposed constructor publishes it as its {@code call}. */
    @Override
    public boolean publishesFacadeCall() {
        return true;
    }

    // ---- lifecycle

    @Override
    public ProtosForeignProviderCompartment openCompartment() {
        return new ProtosForeignProviderCompartment() {
            @Override
            public ProtosForeignProviderId providerId() {
                return ID;
            }

            @Override
            public ProtosForeignProviderSession openSession() {
                return () -> {};
            }

            @Override
            public void close() {}
        };
    }

    /**
     * PLAT052 in-process restriction of this provider: the compartment can reach only the
     * catalogue, which exposes no authority-bearing type, and nothing is provisioned to it.
     */
    static final class CatalogueConfinement
            implements ProtosForeignProviderEnforcement.InProcessRestriction {
        @Override
        public ProtosForeignProviderCompartment openConfinedCompartment(
                ProtosForeignProviderFactory factory) {
            return factory.openCompartment();
        }
    }

    // ---- D188 value contract

    @Override
    public String language() {
        return LANGUAGE;
    }

    @Override
    public ProtosForeignAdmissionDescriptor classify(
            ProtosForeignProviderSession session, Object value) {
        if (value == Absent.NULL) {
            return ProtosForeignAdmissionDescriptor.absent();
        }
        if (value instanceof Boolean bool) {
            return ProtosForeignAdmissionDescriptor.booleanValue(bool);
        }
        if (value instanceof String text) {
            return ProtosForeignAdmissionDescriptor.text(text);
        }
        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger) {
            // The admission descriptor normalizes once at this boundary.
            return ProtosForeignAdmissionDescriptor.integral((Number) value);
        }
        if (value instanceof Double || value instanceof Float) {
            // binary32 widens to binary64 exactly.
            return ProtosForeignAdmissionDescriptor.binary64(((Number) value).doubleValue());
        }
        if (value instanceof ProtosHostJavaCatalogue.Entry entry) {
            return ProtosForeignAdmissionDescriptor.raw(
                    entry,
                    entry.constructor() == null
                            ? EnumSet.of(ProtosForeignAdmissionDescriptor.Capability.MEMBER_READ)
                            : EnumSet.of(
                                    ProtosForeignAdmissionDescriptor.Capability.EXECUTABLE,
                                    ProtosForeignAdmissionDescriptor.Capability.MEMBER_READ));
        }
        if (value instanceof BoundMethod) {
            return ProtosForeignAdmissionDescriptor.raw(
                    null, EnumSet.of(ProtosForeignAdmissionDescriptor.Capability.EXECUTABLE));
        }
        // Every other Java object is identity-bearing: host reference identity, never equals().
        // Explicit member reads reach exactly the exposed-method surface ordinary reads use.
        return ProtosForeignAdmissionDescriptor.raw(
                new HostIdentity(value),
                EnumSet.of(ProtosForeignAdmissionDescriptor.Capability.MEMBER_READ));
    }

    /** No exposed Java member takes a callback; every other kind is checked per member. */
    @Override
    public boolean acceptsArgument(ProtosForeignArgument argument) {
        return !argument.isCallback();
    }

    @Override
    public boolean canFaithfullyReadMember(
            ProtosForeignProviderSession session, Object target, String name) {
        return exposedMethod(target, name) != null;
    }

    /** Reading an exposed method is side-effect free and binds its exact receiver. */
    @Override
    public Object readMember(ProtosForeignProviderSession session, Object target, String name) {
        Method method = Objects.requireNonNull(exposedMethod(target, name), "exposed member");
        return new BoundMethod(
                target instanceof ProtosHostJavaCatalogue.Entry ? null : target, method);
    }

    private Method exposedMethod(Object target, String name) {
        if (target instanceof ProtosHostJavaCatalogue.Entry entry) {
            return entry.staticMethods().get(name);
        }
        if (target instanceof BoundMethod || target == Absent.NULL) {
            return null;
        }
        // Exact class only: an admitted superclass never exposes its methods on a subclass.
        return catalogue.lookup(target.getClass())
                .map(entry -> entry.instanceMethods().get(name))
                .orElse(null);
    }

    @Override
    public Object execute(
            ProtosForeignProviderSession session,
            Object target,
            List<ProtosForeignArgument> arguments)
            throws Exception {
        if (target instanceof ProtosHostJavaCatalogue.Entry entry) {
            return invoke(entry.constructor(), null, arguments);
        }
        BoundMethod bound = (BoundMethod) target;
        return invoke(bound.method(), bound.receiver(), arguments);
    }

    /**
     * Converts every argument losslessly for the exact member before the member runs, then invokes
     * it. A thrown Java exception becomes a sanitized failure; its object never escapes.
     */
    private Object invoke(
            Executable member, Object receiver, List<ProtosForeignArgument> arguments)
            throws Exception {
        Class<?>[] parameters = member.getParameterTypes();
        if (parameters.length != arguments.size()) {
            throw JavaFailure.rejected("argument count does not match the Java member");
        }
        Object[] converted = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            converted[i] = convert(arguments.get(i), parameters[i]);
        }
        Object result;
        try {
            result =
                    member instanceof Method method
                            ? method.invoke(receiver, converted)
                            : ((Constructor<?>) member).newInstance(converted);
        } catch (InvocationTargetException thrown) {
            Throwable cause = thrown.getCause();
            if (cause instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            throw JavaFailure.thrown(cause);
        }
        return result == null ? Absent.NULL : result;
    }

    /** Lossless conversion to {@code type}, or a rejection; there is no broad numeric coercion. */
    private Object convert(ProtosForeignArgument argument, Class<?> type) throws JavaFailure {
        Object value = argument.value();
        try {
            Object converted =
                    switch (argument.kind()) {
                        case NULL -> type.isPrimitive() ? REJECTED : null;
                        case BOOLEAN ->
                                type == boolean.class || type == Boolean.class ? value : REJECTED;
                        case STRING -> type == String.class ? value : REJECTED;
                        case INTEGER -> integral(value, type);
                        case BINARY64 -> binary((Double) value, type);
                        case RAW ->
                                catalogue.lookup(type).isPresent() && type.isInstance(value)
                                        ? value
                                        : REJECTED;
                    };
            if (converted != REJECTED) {
                return converted;
            }
        } catch (ArithmeticException outOfRange) {
            // Falls through to the rejection: narrowing is never lossy.
        }
        throw JavaFailure.rejected("argument has no lossless Java representation");
    }

    /*
     * An Integer argument is a Long within the signed-long range and a BigInteger only beyond it,
     * so fixed-width targets narrow exactly from the long and a BigInteger argument fits none.
     */
    private static Object integral(Object value, Class<?> type) {
        if (type == BigInteger.class) {
            return value instanceof Long small ? BigInteger.valueOf(small) : value;
        }
        if (!(value instanceof Long small)) {
            return REJECTED;
        }
        long exact = small;
        if (type == long.class || type == Long.class) {
            return exact;
        }
        if (type == int.class || type == Integer.class) {
            return exact == (int) exact ? (Object) (int) exact : REJECTED;
        }
        if (type == short.class || type == Short.class) {
            return exact == (short) exact ? (Object) (short) exact : REJECTED;
        }
        if (type == byte.class || type == Byte.class) {
            return exact == (byte) exact ? (Object) (byte) exact : REJECTED;
        }
        return REJECTED;
    }

    private static Object binary(double value, Class<?> type) {
        if (type == double.class || type == Double.class) {
            return value;
        }
        if (type == float.class || type == Float.class) {
            float narrowed = (float) value;
            return Double.compare(narrowed, value) == 0 ? narrowed : REJECTED;
        }
        return REJECTED;
    }

    @Override
    public ProtosForeignFailureDescription describeFailure(Throwable failure) {
        if (failure instanceof JavaFailure java) {
            return new ProtosForeignFailureDescription(
                    java.category, java.foreignCategory, java.safeMessage, null);
        }
        return ProtosForeignFailureDescription.EMPTY;
    }

    // ---- provider-private values

    /** Java {@code null} or a {@code void} result: a true absence, admitted as Protos null. */
    private enum Absent {
        NULL
    }

    /** An exposed method bound to its exact receiver, or to none for a static method. */
    private record BoundMethod(Object receiver, Method method) {}

    /** Stable identity key of one host Java object: reference identity only. */
    private static final class HostIdentity {
        private final Object object;

        HostIdentity(Object object) {
            this.object = object;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof HostIdentity identity && identity.object == object;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(object);
        }
    }

    /**
     * Sanitized entered failure. Only the Java exception class name crosses; its message, stack,
     * cause chain, and object never do, because none of them is known to be safe.
     */
    private static final class JavaFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final String category;
        final String foreignCategory;
        final String safeMessage;

        private JavaFailure(String category, String foreignCategory, String safeMessage) {
            super(category, null, false, false);
            this.category = category;
            this.foreignCategory = foreignCategory;
            this.safeMessage = safeMessage;
        }

        static JavaFailure rejected(String safeMessage) {
            return new JavaFailure("argument", null, safeMessage);
        }

        static JavaFailure thrown(Throwable cause) {
            return new JavaFailure(
                    "exception", cause == null ? null : cause.getClass().getName(), null);
        }
    }
}
