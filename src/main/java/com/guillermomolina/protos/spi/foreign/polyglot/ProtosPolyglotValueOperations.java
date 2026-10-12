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
package com.guillermomolina.protos.spi.foreign.polyglot;

import com.guillermomolina.protos.spi.foreign.ProtosForeignArgumentValue;
import com.guillermomolina.protos.spi.foreign.ProtosForeignFailureInfo;
import com.guillermomolina.protos.spi.foreign.ProtosForeignPluginSession;
import com.guillermomolina.protos.spi.foreign.ProtosForeignValueClass;
import com.guillermomolina.protos.spi.foreign.ProtosForeignValueOperations;
import java.math.BigInteger;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

/**
 * Optional generic value contract for external providers whose handles are Polyglot {@link
 * Value}s, typically Truffle-language providers (I085-A). Nothing in the core SPI requires it.
 *
 * <p>Operations are expressed only through the Polyglot interop surface, so Truffle providers
 * share one projection instead of each writing their own. Booleans and strings convert by their
 * interop category. A null or number converts only by the source family the provider declares;
 * otherwise it stays a handle, because interop exposes physical shape rather than source family.
 * Handles carry no stable identity key: Polyglot gives no generic guarantee that one exists.
 *
 * <p>Reading an invocable member yields an executable bound to its exact receiver and invokes it
 * through {@link Value#invokeMember}, so receiver binding never depends on the language. Failures expose only their category; a guest
 * message, stack, or object is never copied.
 */
public final class ProtosPolyglotValueOperations implements ProtosForeignValueOperations {
    private final String language;
    private final Function<Value, ProtosForeignScalarFamily> scalarFamily;

    /**
     * @param scalarFamily declares the source family of each null or number value
     */
    public ProtosPolyglotValueOperations(
            String language, Function<Value, ProtosForeignScalarFamily> scalarFamily) {
        this.language = Objects.requireNonNull(language, "language");
        this.scalarFamily = Objects.requireNonNull(scalarFamily, "scalarFamily");
    }

    @Override
    public String language() {
        return language;
    }

    @Override
    public boolean supportsLongIntegerArguments() {
        return true;
    }

    @Override
    public ProtosForeignValueClass classify(
            ProtosForeignPluginSession session, Object value) {
        if (value instanceof MemberInvocation) {
            return ProtosForeignValueClass.handle(
                    null, EnumSet.of(ProtosForeignValueClass.Capability.EXECUTABLE));
        }
        Value polyglot = (Value) value;
        if (polyglot.isBoolean()) {
            return ProtosForeignValueClass.booleanValue(polyglot.asBoolean());
        }
        if (polyglot.isString()) {
            return ProtosForeignValueClass.text(polyglot.asString());
        }
        if (polyglot.isNull() || polyglot.isNumber()) {
            ProtosForeignScalarFamily family = scalarFamily.apply(polyglot);
            switch (Objects.requireNonNull(family, "scalar family")) {
                case ABSENT -> {
                    requireDeclared(polyglot.isNull(), family);
                    return ProtosForeignValueClass.absent();
                }
                case INTEGRAL -> {
                    requireDeclared(polyglot.fitsInBigInteger(), family);
                    return polyglot.fitsInLong()
                            ? ProtosForeignValueClass.integral(polyglot.asLong())
                            : ProtosForeignValueClass.integral(polyglot.asBigInteger());
                }
                case BINARY_FLOATING -> {
                    requireDeclared(polyglot.fitsInDouble(), family);
                    return ProtosForeignValueClass.binary64(polyglot.asDouble());
                }
                case UNSPECIFIED -> {}
            }
        }
        EnumSet<ProtosForeignValueClass.Capability> capabilities =
                EnumSet.noneOf(ProtosForeignValueClass.Capability.class);
        if (polyglot.canExecute()) {
            capabilities.add(ProtosForeignValueClass.Capability.EXECUTABLE);
        }
        if (polyglot.canInstantiate()) {
            capabilities.add(ProtosForeignValueClass.Capability.INSTANTIABLE);
        }
        if (polyglot.hasArrayElements()) {
            capabilities.add(ProtosForeignValueClass.Capability.INDEXED_READ);
            capabilities.add(ProtosForeignValueClass.Capability.INDEXED_WRITE);
        }
        // Iterating a hash is ambiguous (keys, values, or entries), so only non-hash iterables
        // project to ordinary each.
        if (polyglot.hasIterator() && !polyglot.hasHashEntries()) {
            capabilities.add(ProtosForeignValueClass.Capability.ITERABLE);
        }
        if (polyglot.hasMembers()) {
            capabilities.add(ProtosForeignValueClass.Capability.MEMBER_READ);
            capabilities.add(ProtosForeignValueClass.Capability.MEMBER_WRITE);
        }
        return ProtosForeignValueClass.handle(null, capabilities);
    }

    private static void requireDeclared(boolean consistent, ProtosForeignScalarFamily family) {
        if (!consistent) {
            throw new IllegalStateException("foreign provider declared an inconsistent " + family);
        }
    }

    @Override
    public boolean canFaithfullyReadMember(
            ProtosForeignPluginSession session, Object target, String name) {
        return target instanceof Value value
                && value.hasMembers()
                && (value.canInvokeMember(name) || value.hasMember(name));
    }

    @Override
    public Object readMember(ProtosForeignPluginSession session, Object target, String name) {
        Value value = (Value) target;
        return value.canInvokeMember(name)
                ? new MemberInvocation(value, name)
                : value.getMember(name);
    }

    @Override
    public Object execute(
            ProtosForeignPluginSession session,
            Object target,
            List<ProtosForeignArgumentValue> arguments) {
        Object[] converted = convert(arguments);
        if (target instanceof MemberInvocation member) {
            return member.receiver().invokeMember(member.name(), converted);
        }
        return ((Value) target).execute(converted);
    }

    @Override
    public Object instantiate(
            ProtosForeignPluginSession session,
            Object target,
            List<ProtosForeignArgumentValue> arguments) {
        return ((Value) target).newInstance(convert(arguments));
    }

    @Override
    public void writeMember(
            ProtosForeignPluginSession session,
            Object target,
            String name,
            ProtosForeignArgumentValue value) {
        ((Value) target).putMember(name, convert(value));
    }

    @Override
    public Object readElement(
            ProtosForeignPluginSession session, Object target, ProtosForeignArgumentValue index) {
        return ((Value) target).getArrayElement(index(index));
    }

    @Override
    public void writeElement(
            ProtosForeignPluginSession session,
            Object target,
            ProtosForeignArgumentValue index,
            ProtosForeignArgumentValue value) {
        ((Value) target).setArrayElement(index(index), convert(value));
    }

    @Override
    public Object openIterator(ProtosForeignPluginSession session, Object target) {
        return ((Value) target).getIterator();
    }

    @Override
    public boolean iteratorHasNext(ProtosForeignPluginSession session, Object iterator) {
        return ((Value) iterator).hasIteratorNextElement();
    }

    @Override
    public Object iteratorNext(ProtosForeignPluginSession session, Object iterator) {
        return ((Value) iterator).getIteratorNextElement();
    }

    @Override
    public ProtosForeignFailureInfo describeFailure(Throwable failure) {
        if (failure instanceof PolyglotException polyglot && polyglot.isGuestException()) {
            return new ProtosForeignFailureInfo("exception", null, null);
        }
        return ProtosForeignFailureInfo.NONE;
    }

    private static Object[] convert(List<ProtosForeignArgumentValue> arguments) {
        Object[] converted = new Object[arguments.size()];
        for (int i = 0; i < converted.length; i++) {
            converted[i] = convert(arguments.get(i));
        }
        return converted;
    }

    /** Lossless host form Polyglot accepts; a handle argument is its exact same-session Value. */
    private static Object convert(ProtosForeignArgumentValue argument) {
        Object value = argument.value();
        if (value instanceof BigInteger integer && integer.bitLength() < Long.SIZE) {
            return integer.longValue();
        }
        return value;
    }

    /**
     * The Polyglot array index denoted by an exact Integer argument. Polyglot array elements are
     * addressed by non-negative signed-64 indices, so any other Integer is rejected exactly here,
     * before the foreign value is touched, and never truncated or wrapped into that domain.
     */
    private static long index(ProtosForeignArgumentValue index) {
        if (index.value() instanceof Long small) {
            if (small < 0L) {
                throw new IllegalArgumentException(
                        "foreign element index is outside the Polyglot array index domain");
            }
            return small;
        }
        if (!(index.value() instanceof BigInteger integer)) {
            throw new IllegalArgumentException("foreign element index is not an Integer");
        }
        if (integer.signum() < 0 || integer.bitLength() >= Long.SIZE) {
            throw new IllegalArgumentException(
                    "foreign element index is outside the Polyglot array index domain");
        }
        return integer.longValue();
    }

    /** An invocable member bound to its exact receiver; never guest-visible except as a handle. */
    private record MemberInvocation(Value receiver, String name) {}
}
