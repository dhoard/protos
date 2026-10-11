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

import static org.junit.jupiter.api.Assertions.assertSame;

import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProtosNumericPrototypeBridgeTest {
    // Deliberately Java-side: exact physical prototype topology is a
    // bootstrap/representation invariant. Source tests exercise the same chain
    // through ordinary lookup without using parent() as an oracle.
    @Test
    void sourceBackedNumericPrototypeHierarchyIsNormativeHierarchy()
            throws IOException {
        ProtosPrelude prelude = corePrelude();

        assertSame(
                prelude.numberPrototype(),
                prelude.integerPrototype().parent().orElseThrow());
        assertSame(
                prelude.integerPrototype(),
                prelude.smallIntegerPrototype().parent().orElseThrow());
        assertSame(
                prelude.integerPrototype(),
                prelude.bigIntegerPrototype().parent().orElseThrow());
        assertSame(
                prelude.numberPrototype(),
                prelude.floatPrototype().parent().orElseThrow());
        assertSame(
                ProtosObjectValue.rootObject(),
                prelude.numberPrototype().parent().orElseThrow());
    }

    private static ProtosPrelude corePrelude() throws IOException {
        return new ProtosCoreBootstrap()
                .bootstrap(Path.of("protos", "lib", "core"));
    }
}
