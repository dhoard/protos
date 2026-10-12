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

import com.guillermomolina.protos.spi.foreign.ProtosForeignArgumentValue;
import com.guillermomolina.protos.spi.foreign.ProtosForeignFailureInfo;
import com.guillermomolina.protos.spi.foreign.ProtosForeignPluginSession;
import com.guillermomolina.protos.spi.foreign.ProtosForeignProviderPlugin;
import com.guillermomolina.protos.spi.foreign.ProtosForeignValueClass;
import com.guillermomolina.protos.spi.foreign.ProtosForeignValueOperations;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Internal bridge from a plugin's public {@link ProtosForeignValueOperations} to the I082 {@link
 * ProtosForeignValueAdapter} (I085-A).
 *
 * <p>It only translates between the public SPI types and the internal D188 types; admission,
 * identity, session generations, capability checks, and failure projection remain the shared
 * substrate's. Handles are opaque to it. No D189 callback crosses the public SPI: a callback
 * argument is rejected before entry.
 */
final class ProtosForeignPluginValueAdapter implements ProtosForeignValueAdapter {
    private final ProtosForeignProviderPlugin plugin;
    /** Obtained on first real use, never while the RuntimeHost registry is built. */
    private volatile ProtosForeignValueOperations operations;

    ProtosForeignPluginValueAdapter(ProtosForeignProviderPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    private ProtosForeignValueOperations operations() {
        ProtosForeignValueOperations current = operations;
        if (current == null) {
            synchronized (this) {
                current = operations;
                if (current == null) {
                    current =
                            Objects.requireNonNull(
                                    ProtosExternalProviderPluginLoader.unchecked(
                                            plugin, plugin::valueOperations),
                                    "foreign value operations");
                    operations = current;
                }
            }
        }
        return current;
    }

    @Override
    public String language() {
        return Objects.requireNonNull(operations().language(), "foreign language");
    }

    @Override
    public ProtosForeignAdmissionDescriptor classify(
            ProtosForeignProviderSession session, Object value) throws Exception {
        ProtosForeignValueClass declared =
                Objects.requireNonNull(
                        operations().classify(plugin(session), value), "foreign classification");
        return switch (declared.kind()) {
            case BOOLEAN ->
                    ProtosForeignAdmissionDescriptor.booleanValue((Boolean) declared.scalar());
            case NULL -> ProtosForeignAdmissionDescriptor.absent();
            case STRING -> ProtosForeignAdmissionDescriptor.text((String) declared.scalar());
            case INTEGER ->
                    ProtosForeignAdmissionDescriptor.integral(
                            (Number) declared.scalar());
            case BINARY64 -> ProtosForeignAdmissionDescriptor.binary64((Double) declared.scalar());
            case HANDLE -> {
                EnumSet<ProtosForeignAdmissionDescriptor.Capability> capabilities =
                        EnumSet.noneOf(ProtosForeignAdmissionDescriptor.Capability.class);
                for (ProtosForeignValueClass.Capability capability : declared.capabilities()) {
                    capabilities.add(
                            ProtosForeignAdmissionDescriptor.Capability.valueOf(capability.name()));
                }
                yield ProtosForeignAdmissionDescriptor.raw(declared.identityKey(), capabilities);
            }
        };
    }

    @Override
    public boolean acceptsArgument(ProtosForeignArgument argument) {
        return !argument.isCallback() && operations().acceptsArgument(publicArgument(argument));
    }

    @Override
    public boolean canFaithfullyReadMember(
            ProtosForeignProviderSession session, Object target, String name) throws Exception {
        return operations().canFaithfullyReadMember(plugin(session), target, name);
    }

    @Override
    public Object readMember(ProtosForeignProviderSession session, Object target, String name)
            throws Exception {
        return operations().readMember(plugin(session), target, name);
    }

    @Override
    public Object execute(
            ProtosForeignProviderSession session, Object target, List<ProtosForeignArgument> args)
            throws Exception {
        return operations().execute(plugin(session), target, publicArguments(args));
    }

    @Override
    public Object instantiate(
            ProtosForeignProviderSession session, Object target, List<ProtosForeignArgument> args)
            throws Exception {
        return operations().instantiate(plugin(session), target, publicArguments(args));
    }

    @Override
    public void writeMember(
            ProtosForeignProviderSession session,
            Object target,
            String name,
            ProtosForeignArgument value)
            throws Exception {
        operations().writeMember(plugin(session), target, name, publicArgument(value));
    }

    @Override
    public Object readElement(
            ProtosForeignProviderSession session, Object target, ProtosForeignArgument index)
            throws Exception {
        return operations().readElement(plugin(session), target, publicArgument(index));
    }

    @Override
    public void writeElement(
            ProtosForeignProviderSession session,
            Object target,
            ProtosForeignArgument index,
            ProtosForeignArgument value)
            throws Exception {
        operations().writeElement(
                plugin(session), target, publicArgument(index), publicArgument(value));
    }

    @Override
    public Object openIterator(ProtosForeignProviderSession session, Object target)
            throws Exception {
        return operations().openIterator(plugin(session), target);
    }

    @Override
    public boolean iteratorHasNext(ProtosForeignProviderSession session, Object iterator)
            throws Exception {
        return operations().iteratorHasNext(plugin(session), iterator);
    }

    @Override
    public Object iteratorNext(ProtosForeignProviderSession session, Object iterator)
            throws Exception {
        return operations().iteratorNext(plugin(session), iterator);
    }

    @Override
    public ProtosForeignFailureDescription describeFailure(Throwable failure) {
        ProtosForeignFailureInfo info = operations().describeFailure(failure);
        if (info == null) {
            return ProtosForeignFailureDescription.EMPTY;
        }
        return new ProtosForeignFailureDescription(
                info.category(), info.foreignCategory(), info.message(), null);
    }

    private static ProtosForeignPluginSession plugin(ProtosForeignProviderSession session) {
        return ((ProtosForeignPluginProvider.Session) session).plugin;
    }

    private List<ProtosForeignArgumentValue> publicArguments(
            List<ProtosForeignArgument> arguments) {
        return arguments.stream().map(this::publicArgument).toList();
    }

    private ProtosForeignArgumentValue publicArgument(ProtosForeignArgument argument) {
        if (argument.isCallback()) {
            throw new IllegalStateException("callback arguments do not cross the public SPI");
        }
        ProtosForeignValueClass.Kind kind =
                argument.kind() == ProtosForeignAdmissionDescriptor.Kind.RAW
                        ? ProtosForeignValueClass.Kind.HANDLE
                        : ProtosForeignValueClass.Kind.valueOf(argument.kind().name());
        Object value = argument.value();
        if (kind == ProtosForeignValueClass.Kind.INTEGER
                && value instanceof Long small
                && !operations().supportsLongIntegerArguments()) {
            value = java.math.BigInteger.valueOf(small);
        }
        return new ProtosForeignArgumentValue(kind, value);
    }
}
