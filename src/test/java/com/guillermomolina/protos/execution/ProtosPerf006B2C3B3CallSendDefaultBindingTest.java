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
 */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosLexicalFallback;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosNullValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalClosure;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.guillermomolina.protos.source.SourceSpan;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.source.Source;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

final class ProtosPerf006B2C3B3CallSendDefaultBindingTest {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF = LanguageReference.create(ProtosLanguage.class);

    @Test
    void omittedCallDefaultComposesSuspensionWithoutReplay() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue finalValue = new ProtosObjectValue(ProtosObjectValue.rootObject());
                String childChars = "() => { null }";
                Source childSource = Source.newBuilder(ProtosLanguage.ID, childChars, "perf006-b2c3b3-child.protos").build();
                CanonicalClosure childDef = closureDefinition(childChars);
                ProtosSemanticBytecodeRootNode childRoot = yieldingLeafRoot(language, childSource, childDef.body().span(), finalValue);
                ProtosClosureValue child = semanticClosure(childDef, ProtosClosureExecutionPlan.bytecode(childDef, language, childSource, childRoot, ProtosExecutionMode.NORMAL), module);
                module.context().createLocalSlot("child", child);

                String chars = "(first = child(), second = first) => { second }";
                Source source = Source.newBuilder(ProtosLanguage.ID, chars, "perf006-b2c3b3-default-call.protos").build();
                CanonicalClosure def = closureDefinition(chars);
                ProtosClosureValue semantic = semanticClosure(def, ProtosClosureExecutionPlan.bytecode(def, language, source, ProtosExecutionMode.NORMAL), module);
                ProtosActivation invocation = ProtosActivation.forClosureInvocation(
                        semantic, List.of(), module.prelude().orElseThrow(), module.actorModuleState(),
                        module.currentModuleKey().orElse(null), module.executionDomain());

                Object first = new ProtosBytecodeClosureExecutionPlan(def, language, source, ProtosExecutionMode.NORMAL).executeActivation(invocation);
                ContinuationResult parent = assertInstanceOf(ContinuationResult.class, first);
                assertInstanceOf(ContinuationResult.class, parent.getResult());
                assertTrue(ProtosLexicalFallback.readByName(invocation, "first").isEmpty());
                Object completed = parent.continueWith(ProtosNullValue.INSTANCE);
                assertSame(finalValue, completed);
                assertSame(finalValue, ProtosLexicalFallback.readByName(invocation, "first").orElseThrow());
                assertSame(finalValue, ProtosLexicalFallback.readByName(invocation, "second").orElseThrow());
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF006_B2C3B3_CALL_DEFAULT_SUSPENSION=PASS");
        System.out.println("PERF006_B2C3B3_DEFAULT_NO_REPLAY=PASS");
    }

    @Test
    void omittedSendDefaultPreservesImmediateMethodReceiverAndHome() throws Exception {
        try (Context context = Context.newBuilder(ProtosLanguage.ID).build()) {
            context.initialize(ProtosLanguage.ID);
            context.enter();
            try {
                ProtosLanguage language = LANGUAGE_REF.get(null);
                ProtosActivation module = moduleActivation();
                ProtosObjectValue receiver = new ProtosObjectValue(ProtosObjectValue.rootObject());
                ProtosObjectValue marker = new ProtosObjectValue(ProtosObjectValue.rootObject());
                module.context().createLocalSlot("marker", marker);
                String methodChars = "() => { marker }";
                Source methodSource = Source.newBuilder(ProtosLanguage.ID, methodChars, "perf006-b2c3b3-method.protos").build();
                CanonicalClosure methodDef = closureDefinition(methodChars);
                receiver.createLocalSlot("pick", semanticClosure(methodDef, ProtosClosureExecutionPlan.bytecode(methodDef, language, methodSource, ProtosExecutionMode.NORMAL), module));

                String chars = "(head, fallback = head.pick()) => { fallback }";
                Source source = Source.newBuilder(ProtosLanguage.ID, chars, "perf006-b2c3b3-default-send.protos").build();
                CanonicalClosure def = closureDefinition(chars);
                ProtosClosureValue semantic = semanticClosure(def, ProtosClosureExecutionPlan.bytecode(def, language, source, ProtosExecutionMode.NORMAL), module);
                ProtosActivation invocation = ProtosActivation.forClosureInvocation(
                        semantic, List.of(receiver), module.prelude().orElseThrow(), module.actorModuleState(),
                        module.currentModuleKey().orElse(null), module.executionDomain());
                Object result = new ProtosBytecodeClosureExecutionPlan(def, language, source, ProtosExecutionMode.NORMAL).executeActivation(invocation);
                assertSame(marker, result);
                assertSame(marker, ProtosLexicalFallback.readByName(invocation, "fallback").orElseThrow());
            } finally {
                context.leave();
            }
        }
        System.out.println("PERF006_B2C3B3_SEND_DEFAULT=PASS");
        System.out.println("PERF006_B2C3B3_IMMEDIATE_METHOD_RECEIVER_HOME=PASS");
    }

    private static ProtosSemanticBytecodeRootNode yieldingLeafRoot(ProtosLanguage language, Source source, SourceSpan span, Object finalValue) {
        BytecodeRootNodes<ProtosSemanticBytecodeRootNode> roots = ProtosSemanticBytecodeRootNodeGen.create(language, BytecodeConfig.DEFAULT, builder -> {
            builder.beginSource(source);
            builder.beginSourceSection(span.startOffset(), span.length());
            builder.beginRoot();
            builder.beginYield(); builder.emitLoadArgument(0); builder.endYield();
            builder.beginReturn(); builder.emitLoadConstant(finalValue); builder.endReturn();
            builder.endRoot(); builder.endSourceSection(); builder.endSource();
        });
        return roots.getNode(0);
    }

    private static ProtosClosureValue semanticClosure(CanonicalClosure def, ProtosClosureExecutionPlan plan, ProtosActivation creator) {
        return new ProtosClosureValue(def, creator.lexicalContextsForClosureCapture(), creator.receiver(),
                creator.methodHome().orElse(null), creator.returnHome().orElse(null), creator.prelude().orElseThrow(), plan);
    }

    private static CanonicalClosure closureDefinition(String chars) {
        return assertInstanceOf(CanonicalClosure.class, canonicalize(chars).expressions().get(0));
    }

    private static CanonicalSequence canonicalize(String chars) {
        return (CanonicalSequence) new Canonicalizer().canonicalize(new ProtosParser(chars).parseProgram());
    }

    private static ProtosActivation moduleActivation() {
        ProtosStandardObjectProtocol.install();
        ProtosObjectValue contextPrototype = new ProtosObjectValue(ProtosObjectValue.rootObject());
        ProtosObjectValue bindings = new ProtosObjectValue(contextPrototype);
        bindings.createLocalSlot("Context", contextPrototype);
        bindings.createLocalSlot("Error", new ProtosObjectValue(ProtosObjectValue.rootObject()));
        bindings.createLocalSlot("Array", new ProtosObjectValue(ProtosObjectValue.rootObject()));
        bindings.freeze();
        return new ProtosPrelude(bindings, contextPrototype).newModuleActivation();
    }
}
