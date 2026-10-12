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
package i085b.libc;

import com.guillermomolina.protos.spi.foreign.ProtosForeignArgumentValue;
import com.guillermomolina.protos.spi.foreign.ProtosForeignPluginEnvironment;
import com.guillermomolina.protos.spi.foreign.ProtosForeignPluginSession;
import com.guillermomolina.protos.spi.foreign.ProtosForeignProviderPlugin;
import com.guillermomolina.protos.spi.foreign.ProtosForeignValueClass;
import com.guillermomolina.protos.spi.foreign.ProtosForeignValueOperations;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Set;

/**
 * I085-B external fixture: the {@code oslib:libc} module whose {@code strlen} operation calls the
 * real C library {@code strlen} through the JDK 25 Foreign Function and Memory API. Targets Linux
 * x86_64 (LP64, so {@code size_t} is a Java {@code long}). The native code runs in the Protos
 * process under the host-selected trusted in-process authority; it is not isolated.
 */
public final class LibcProvider implements ProtosForeignProviderPlugin {
    private static final String ID = "i085b-libc";

    /** Module handle owning the {@code strlen} downcall linked for one session. */
    public static final class Libc {
        private final MethodHandle strlen;

        Libc(MethodHandle strlen) {
            this.strlen = strlen;
        }

        long strlen(String text) throws Throwable {
            if (text.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("strlen argument contains NUL");
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment cString = arena.allocateFrom(text);
                return (long) strlen.invokeExact(cString);
            }
        }
    }

    /** The {@code strlen} member bound to its exact receiver. */
    record Bound(Libc receiver) {}

    public LibcProvider() {
        System.err.println("I085-B-TRACE provider-loaded " + ID);
    }

    public String providerId() {
        return ID;
    }

    public String scheme() {
        return "oslib";
    }

    public String canonicalTarget(String target) {
        if (!target.equals("libc")) {
            throw new IllegalArgumentException("unknown module: " + target);
        }
        return target;
    }

    public ProtosForeignPluginSession openSession(ProtosForeignPluginEnvironment environment) {
        System.err.println("I085-B-TRACE session-open " + ID);
        Linker linker = Linker.nativeLinker();
        MethodHandle strlen =
                linker.downcallHandle(
                        linker.defaultLookup().find("strlen").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        return new ProtosForeignPluginSession() {
            public Object acquireModule(String target) {
                return new Libc(strlen);
            }

            public void close() {}
        };
    }

    public ProtosForeignValueOperations valueOperations() {
        return new ProtosForeignValueOperations() {
            public String language() {
                return ID;
            }

            public ProtosForeignValueClass classify(
                    ProtosForeignPluginSession session, Object handle) {
                if (handle instanceof Long number) {
                    return ProtosForeignValueClass.integral(number);
                }
                if (handle instanceof Bound) {
                    return ProtosForeignValueClass.handle(
                            null, Set.of(ProtosForeignValueClass.Capability.EXECUTABLE));
                }
                return ProtosForeignValueClass.handle(
                        handle, Set.of(ProtosForeignValueClass.Capability.MEMBER_READ));
            }

            public boolean acceptsArgument(ProtosForeignArgumentValue argument) {
                return argument.kind() == ProtosForeignValueClass.Kind.STRING;
            }

            public boolean canFaithfullyReadMember(
                    ProtosForeignPluginSession session, Object handle, String name) {
                return handle instanceof Libc && name.equals("strlen");
            }

            public Object readMember(
                    ProtosForeignPluginSession session, Object handle, String name) {
                return new Bound((Libc) handle);
            }

            public Object execute(
                    ProtosForeignPluginSession session,
                    Object handle,
                    List<ProtosForeignArgumentValue> arguments) {
                if (arguments.size() != 1) {
                    throw new IllegalArgumentException("strlen expects one argument");
                }
                try {
                    return ((Bound) handle).receiver().strlen((String) arguments.get(0).value());
                } catch (RuntimeException | Error failure) {
                    throw failure;
                } catch (Throwable failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };
    }
}
