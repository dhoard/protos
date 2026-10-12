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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosCoreErrors.StandardError;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import java.io.IOException;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ProtosInvalidSuperExecutionTest {
    @Test
    void installsInvalidSuperAsDirectErrorChildAndCreatesFreshOccurrences()
            throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation activation = prelude.newModuleActivation();
        ProtosObjectValue prototype =
                assertInstanceOf(
                        ProtosObjectValue.class,
                        prelude.bindings().readLocalSlot("InvalidSuper").orElseThrow());

        assertSame(prelude.errorPrototype(), prototype.parent().orElseThrow());

        ProtosObjectValue first = ProtosCoreErrors.newInvalidSuper(activation);
        ProtosObjectValue second = ProtosCoreErrors.newInvalidSuper(activation);
        assertNotSame(first, second);
        assertSame(prototype, first.parent().orElseThrow());
        assertSame(prototype, second.parent().orElseThrow());
    }

    @Test
    void invalidSuperHappensAfterArgumentsExactlyOnceAndLeftToRight()
            throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation activation = prelude.newModuleActivation();

        ProtosSignalException signal =
                assertThrows(
                        ProtosSignalException.class,
                        () ->
                                execute(
                                        activation,
                                        """
                                        order: 0
                                        record: (digit) => {
                                            order = order * 10 + digit
                                            digit
                                        }
                                        runner: () => {
                                            super.noSuchSelector(record(1), record(2))
                                        }
                                        runner()
                                        """));

        assertSame(
                ProtosCoreErrors.prototype(activation, StandardError.INVALID_SUPER),
                signal.error().parent().orElseThrow());
        assertEquals(String.valueOf("12"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(activation.context().readLocalSlot("order").orElseThrow()));
    }

    @Test
    void spreadExpressionPreservesArgumentOrderBeforeInvalidSuper()
            throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation activation = prelude.newModuleActivation();

        ProtosSignalException signal =
                assertThrows(
                        ProtosSignalException.class,
                        () ->
                                execute(
                                        activation,
                                        """
                                        order: 0
                                        record: (digit) => {
                                            order = order * 10 + digit
                                            digit
                                        }
                                        spreadValues: () => {
                                            record(2)
                                            Array(3, 4)
                                        }
                                        runner: () => {
                                            super.noSuchSelector(
                                                record(1),
                                                ...spreadValues(),
                                                record(5)
                                            )
                                        }
                                        runner()
                                        """));

        assertSame(
                ProtosCoreErrors.prototype(activation, StandardError.INVALID_SUPER),
                signal.error().parent().orElseThrow());
        assertEquals(String.valueOf("125"), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(activation.context().readLocalSlot("order").orElseThrow()));
    }

    @Test
    void missingMethodHomeDoesNotFallBackToReceiverLookup() throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosActivation activation = prelude.newModuleActivation();

        execute(
                activation,
                """
                noSuchSelector: () => { 42 }
                null
                """);

        ProtosSignalException signal =
                assertThrows(
                        ProtosSignalException.class,
                        () ->
                                ProtosInvocation.invokeSuperMessage(
                                        "noSuchSelector", List.of(), activation));

        assertSame(
                ProtosCoreErrors.prototype(activation, StandardError.INVALID_SUPER),
                signal.error().parent().orElseThrow());
    }

    @Test
    void rootMethodHomeHasEmptySuperSearchAndSignalsSlotNotFound()
            throws IOException {
        ProtosPrelude prelude = corePrelude();
        ProtosClosureValue closure =
                assertInstanceOf(
                        ProtosClosureValue.class,
                        execute(prelude.newModuleActivation(), "() => { null }"));
        ProtosObjectValue root = ProtosObjectValue.rootObject();
        ProtosActivation caller =
                ProtosActivation.forClosureInvocation(
                        closure.bindMethod(root, root), List.of(), prelude);

        ProtosSignalException signal =
                assertThrows(
                        ProtosSignalException.class,
                        () ->
                                ProtosInvocation.invokeSuperMessage(
                                        "noSuchSelector", List.of(), caller));

        assertSame(
                ProtosCoreErrors.prototype(caller, StandardError.SLOT_NOT_FOUND),
                signal.error().parent().orElseThrow());
    }

    private static Object execute(ProtosActivation activation, String source) {
        return ProtosTestExecutionSupport.evaluate(source, activation);
    }

    private static ProtosPrelude corePrelude() throws IOException {
        return new ProtosCoreBootstrap()
                .bootstrap(Path.of("protos", "lib", "core"));
    }
}
