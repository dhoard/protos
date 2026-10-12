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
package i085b.opaque;

import com.guillermomolina.protos.spi.foreign.ProtosForeignArgumentValue;
import com.guillermomolina.protos.spi.foreign.ProtosForeignPluginEnvironment;
import com.guillermomolina.protos.spi.foreign.ProtosForeignPluginSession;
import com.guillermomolina.protos.spi.foreign.ProtosForeignProviderPlugin;
import com.guillermomolina.protos.spi.foreign.ProtosForeignValueClass;
import com.guillermomolina.protos.spi.foreign.ProtosForeignValueOperations;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;

/**
 * I085-B external fixture: the {@code inventado:demo} module with one {@code double} operation
 * over an opaque Java handle. Built against only the public SPI in the extracted portable
 * distribution; it reports provider loading and session opening on standard error so the runner
 * can observe pay-as-you-grow behavior from a separate process.
 */
public final class OpaqueProvider implements ProtosForeignProviderPlugin {
    private static final String ID = "i085b-opaque";

    /** Ordinary Java module handle with one operation. */
    public static final class Demo {
        long twice(long value) {
            return Math.multiplyExact(value, 2);
        }
    }

    /** The {@code double} member bound to its exact receiver. */
    record Bound(Demo receiver) {}

    public OpaqueProvider() {
        System.err.println("I085-B-TRACE provider-loaded " + ID);
    }

    public String providerId() {
        return ID;
    }

    public String scheme() {
        return "inventado";
    }

    public String canonicalTarget(String target) {
        if (!target.equals("demo")) {
            throw new IllegalArgumentException("unknown module: " + target);
        }
        return target;
    }

    public ProtosForeignPluginSession openSession(ProtosForeignPluginEnvironment environment) {
        System.err.println("I085-B-TRACE session-open " + ID);
        return new ProtosForeignPluginSession() {
            public Object acquireModule(String target) {
                return new Demo();
            }

            public void close() {}
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
                return argument.kind() == ProtosForeignValueClass.Kind.INTEGER;
            }

            public boolean canFaithfullyReadMember(
                    ProtosForeignPluginSession session, Object handle, String name) {
                return handle instanceof Demo && name.equals("double");
            }

            public Object readMember(
                    ProtosForeignPluginSession session, Object handle, String name) {
                return new Bound((Demo) handle);
            }

            public Object execute(
                    ProtosForeignPluginSession session,
                    Object handle,
                    List<ProtosForeignArgumentValue> arguments) {
                if (arguments.size() != 1) {
                    throw new IllegalArgumentException("double expects one argument");
                }
                Object argument = arguments.get(0).value();
                long value = argument instanceof Long small
                        ? small.longValue()
                        : ((BigInteger) argument).longValueExact();
                return ((Bound) handle).receiver().twice(value);
            }
        };
    }
}
