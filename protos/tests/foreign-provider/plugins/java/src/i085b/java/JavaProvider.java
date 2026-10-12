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
package i085b.java;

import com.guillermomolina.protos.spi.foreign.ProtosForeignArgumentValue;
import com.guillermomolina.protos.spi.foreign.ProtosForeignPluginEnvironment;
import com.guillermomolina.protos.spi.foreign.ProtosForeignPluginSession;
import com.guillermomolina.protos.spi.foreign.ProtosForeignProviderPlugin;
import com.guillermomolina.protos.spi.foreign.ProtosForeignValueClass;
import com.guillermomolina.protos.spi.foreign.ProtosForeignValueOperations;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * I085-B external fixture: the {@code java:math} and {@code java:date} modules backed by the real
 * JDK {@link Math} and {@link LocalDate} classes. Every operation names its exact Java overload
 * here; Protos performs no overload resolution. Arguments are converted exactly: integers must
 * fit a Java {@code long}, and any other argument kind is rejected.
 */
public final class JavaProvider implements ProtosForeignProviderPlugin {
    private static final String ID = "i085b-java";

    /** One module handle: its name and the operations it exposes. */
    public record Module(String name, Set<String> operations) {}

    /** An operation bound to its exact module receiver. */
    record Bound(Module receiver, String operation) {}

    private static final Map<String, Module> MODULES =
            Map.of(
                    "math", new Module("math", Set.of("abs", "max")),
                    "date", new Module("date", Set.of("year", "month")));

    public JavaProvider() {
        System.err.println("I085-B-TRACE provider-loaded " + ID);
    }

    public String providerId() {
        return ID;
    }

    public String scheme() {
        return "java";
    }

    public String canonicalTarget(String target) {
        if (!MODULES.containsKey(target)) {
            throw new IllegalArgumentException("unknown module: " + target);
        }
        return target;
    }

    public ProtosForeignPluginSession openSession(ProtosForeignPluginEnvironment environment) {
        System.err.println("I085-B-TRACE session-open " + ID);
        // Evidence that the operations are the JDK's own classes, not fixture copies.
        System.err.println(
                "I085-B-TRACE jdk-origin "
                        + Math.class.getModule().getName()
                        + " "
                        + LocalDate.class.getModule().getName());
        return new ProtosForeignPluginSession() {
            public Object acquireModule(String target) {
                return MODULES.get(target);
            }

            public void close() {}
        };
    }

    private static long longArgument(List<ProtosForeignArgumentValue> arguments, int index) {
        ProtosForeignArgumentValue argument = arguments.get(index);
        if (argument.kind() != ProtosForeignValueClass.Kind.INTEGER) {
            throw new IllegalArgumentException("expected an Integer argument");
        }
        Object value = argument.value();
        return value instanceof Long small
                ? small.longValue()
                : ((BigInteger) value).longValueExact();
    }

    private static LocalDate dateArgument(List<ProtosForeignArgumentValue> arguments) {
        ProtosForeignArgumentValue argument = arguments.get(0);
        if (argument.kind() != ProtosForeignValueClass.Kind.STRING) {
            throw new IllegalArgumentException("expected a String argument");
        }
        return LocalDate.parse((String) argument.value());
    }

    private static long invoke(String operation, List<ProtosForeignArgumentValue> arguments) {
        int arity = operation.equals("max") ? 2 : 1;
        if (arguments.size() != arity) {
            throw new IllegalArgumentException(operation + " expects " + arity + " argument(s)");
        }
        return switch (operation) {
            case "abs" -> Math.abs(longArgument(arguments, 0));
            case "max" -> Math.max(longArgument(arguments, 0), longArgument(arguments, 1));
            case "year" -> dateArgument(arguments).getYear();
            case "month" -> dateArgument(arguments).getMonthValue();
            default -> throw new IllegalStateException(operation);
        };
    }

    public ProtosForeignValueOperations valueOperations() {
        return new ProtosForeignValueOperations() {
            public String language() {
                return ID;
            }

            @Override
            public boolean supportsLongIntegerArguments() {
                return true;
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
                return argument.kind() == ProtosForeignValueClass.Kind.INTEGER
                        || argument.kind() == ProtosForeignValueClass.Kind.STRING;
            }

            public boolean canFaithfullyReadMember(
                    ProtosForeignPluginSession session, Object handle, String name) {
                return handle instanceof Module module && module.operations().contains(name);
            }

            public Object readMember(
                    ProtosForeignPluginSession session, Object handle, String name) {
                return new Bound((Module) handle, name);
            }

            public Object execute(
                    ProtosForeignPluginSession session,
                    Object handle,
                    List<ProtosForeignArgumentValue> arguments) {
                return invoke(((Bound) handle).operation(), arguments);
            }
        };
    }
}
