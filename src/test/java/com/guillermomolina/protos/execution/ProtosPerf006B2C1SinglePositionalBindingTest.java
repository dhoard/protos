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

import static org.junit.jupiter.api.Assertions.*;
import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.*;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.*;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B2C1SinglePositionalBindingTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF = LanguageReference.create(ProtosLanguage.class);

    @Test
    void oneRequiredParameterReceivesOneSourceArgument() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID); context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue token = new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("token", token);
                String closureChars = "(item) => { item }";
                Source closureSource = Source.newBuilder(ProtosLanguage.ID, closureChars, "perf006-b2c1-closure.protos").build();
                CanonicalClosure def = closureDefinition(closureChars);
                ProtosClosureValue closure = semanticClosure(def, ProtosClosureExecutionPlan.bytecode(def, language, closureSource, ProtosExecutionMode.NORMAL), module);
                module.context().createLocalSlot("entry", closure);
                String topChars = "entry(token)";
                Source topSource = Source.newBuilder(ProtosLanguage.ID, topChars, "perf006-b2c1-top.protos").build();
                ProtosSemanticBytecodeRootNode top = new CanonicalToBytecodeLowerer(language, topSource, ProtosExecutionMode.NORMAL).lowerRoot(canonicalize(topChars));
                assertSame(token, top.getCallTarget().call(module));
            } finally { context.leave(); }
        }
        System.out.println("PERF006_B2C1_REQUIRED_PARAMETER_BINDING=PASS");
    }

    @Test
    void requiredParameterArityMismatchIsGuestError() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID); context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                String closureChars = "(item) => { item }";
                Source closureSource = Source.newBuilder(ProtosLanguage.ID, closureChars, "perf006-b2c1-arity-closure.protos").build();
                CanonicalClosure def = closureDefinition(closureChars);
                module.context().createLocalSlot("entry", semanticClosure(def, ProtosClosureExecutionPlan.bytecode(def, language, closureSource, ProtosExecutionMode.NORMAL), module));
                String topChars = "entry()";
                Source topSource = Source.newBuilder(ProtosLanguage.ID, topChars, "perf006-b2c1-arity-top.protos").build();
                ProtosSemanticBytecodeRootNode top = new CanonicalToBytecodeLowerer(language, topSource, ProtosExecutionMode.NORMAL).lowerRoot(canonicalize(topChars));
                assertThrows(ProtosSignalException.class, () -> top.getCallTarget().call(module));
            } finally { context.leave(); }
        }
        System.out.println("PERF006_B2C1_ARITY_ERROR=PASS");
    }

    private static ProtosClosureValue semanticClosure(CanonicalClosure d, ProtosClosureExecutionPlan p, ProtosActivation c) {
        return new ProtosClosureValue(d, c.lexicalContextsForClosureCapture(), c.receiver(), c.methodHome().orElse(null), c.returnHome().orElse(null), c.prelude().orElseThrow(), p);
    }
    private static CanonicalClosure closureDefinition(String s) { return assertInstanceOf(CanonicalClosure.class, canonicalize(s).expressions().get(0)); }
    private static CanonicalSequence canonicalize(String s) { return (CanonicalSequence)new Canonicalizer().canonicalize(new ProtosParser(s).parseProgram()); }
    private static ProtosActivation moduleActivation() {
        ProtosStandardObjectProtocol.install();
        ProtosObjectValue cp = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue b = new ProtosObjectValue(cp);
        b.createLocalSlot("Context", cp);
        b.createLocalSlot("Error", new ProtosObjectValue(ProtosObjectValue.rootObject()));
        b.createLocalSlot("Array", new ProtosObjectValue(ProtosObjectValue.rootObject()));
        b.freeze();
        return new ProtosPrelude(b, cp).newModuleActivation();
    }
}
