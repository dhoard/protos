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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.parser.ast.SurfaceSequence;
import com.guillermomolina.protos.runtime.ProtosNonLocalReturnException;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosReturnHome;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.source.Source;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B4ATransferSubstrateTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    @Test
    void guestErrorLaneIsTruffleCatchableWithoutReplacingTheProtosOccurrence() {
        ProtosObjectValue error = ProtosObjectValue.rootObject();
        ProtosSignalException signal = new ProtosSignalException(error);

        assertInstanceOf(AbstractTruffleException.class, signal);
        assertSame(error, signal.error());

        System.out.println("PERF006_B4A_GUEST_ERROR_TRUFFLE_LANE=PASS");
        System.out.println("PERF006_B4A_GUEST_ERROR_EXACT_OCCURRENCE=YES");
    }

    @Test
    void internalControlFamiliesStayDistinctFromTheGuestErrorLane() {
        ProtosReturnHome home = new ProtosReturnHome();
        Object value = new Object();
        ProtosNonLocalReturnException nonLocalReturn =
                new ProtosNonLocalReturnException(home, value);
        ProtosTaskCancellationException cancellation =
                new ProtosTaskCancellationException();

        assertInstanceOf(ControlFlowException.class, nonLocalReturn);
        assertInstanceOf(ControlFlowException.class, cancellation);
        assertFalse(AbstractTruffleException.class.isInstance(nonLocalReturn));
        assertFalse(AbstractTruffleException.class.isInstance(cancellation));
        assertSame(home, nonLocalReturn.target());
        assertSame(value, nonLocalReturn.value());

        System.out.println("PERF006_B4A_NLR_CONTROL_LANE=PASS");
        System.out.println("PERF006_B4A_CANCELLATION_CONTROL_LANE=PASS");
    }

    @Test
    void bytecodeEhBridgePreservesExactInternalTransferIdentityAndRejectsForeignControl()
            throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosSemanticBytecodeRootNode root = bytecodeRoot("null");
                ProtosReturnHome home = new ProtosReturnHome();
                Object value = ProtosNullValue.INSTANCE;
                ProtosNonLocalReturnException nonLocalReturn =
                        new ProtosNonLocalReturnException(home, value);

                ProtosBytecodeControlTransferException bridgedReturn =
                        assertThrows(
                                ProtosBytecodeControlTransferException.class,
                                () ->
                                        root.interceptControlFlowException(
                                                nonLocalReturn,
                                                null,
                                                null,
                                                0));
                assertSame(nonLocalReturn, bridgedReturn.transfer());
                assertSame(home, nonLocalReturn.target());
                assertSame(value, nonLocalReturn.value());

                ProtosTaskCancellationException cancellation =
                        new ProtosTaskCancellationException();
                ProtosBytecodeControlTransferException bridgedCancellation =
                        assertThrows(
                                ProtosBytecodeControlTransferException.class,
                                () ->
                                        root.interceptControlFlowException(
                                                cancellation,
                                                null,
                                                null,
                                                0));
                assertSame(cancellation, bridgedCancellation.transfer());

                ControlFlowException foreign = new ControlFlowException();
                ControlFlowException observedForeign =
                        assertThrows(
                                ControlFlowException.class,
                                () ->
                                        root.interceptControlFlowException(
                                                foreign,
                                                null,
                                                null,
                                                0));
                assertSame(foreign, observedForeign);
            } finally {
                context.leave();
            }
        }

        System.out.println("PERF006_B4A_NARROW_EH_BRIDGE=PASS");
        System.out.println("PERF006_B4A_FOREIGN_CONTROL_BRIDGED=NO");
    }

    @Test
    void bridgeIsPackagePrivateAndCarriesNoGlobalMutableAuthority() {
        assertFalse(
                Modifier.isPublic(
                        ProtosBytecodeControlTransferException.class.getModifiers()));
        assertTrue(
                Arrays.stream(
                                ProtosBytecodeControlTransferException.class
                                        .getDeclaredFields())
                        .noneMatch(
                                field ->
                                        Modifier.isStatic(field.getModifiers())
                                                && !Modifier.isFinal(field.getModifiers())));

        System.out.println("PERF006_B4A_BRIDGE_PUBLIC=NO");
        System.out.println("PERF006_B4A_GLOBAL_TRANSFER_AUTHORITY=NO");
    }

    private static ProtosSemanticBytecodeRootNode bytecodeRoot(String characters) {
        ProtosLanguage language = LANGUAGE_REF.get(null);
        Source source =
                Source.newBuilder(
                                ProtosLanguage.ID,
                                characters,
                                "perf006-b4a-transfer-substrate.protos")
                        .build();
        return new CanonicalToBytecodeLowerer(language, source, ProtosExecutionMode.NORMAL)
                .lowerRoot(canonicalize(characters));
    }

    private static CanonicalSequence canonicalize(String characters) {
        SurfaceSequence surface = new ProtosParser(characters).parseProgram();
        return (CanonicalSequence) new Canonicalizer().canonicalize(surface);
    }
}
