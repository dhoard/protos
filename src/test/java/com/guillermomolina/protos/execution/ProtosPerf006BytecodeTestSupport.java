/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. See LICENSE.TXT.
 */
package com.guillermomolina.protos.execution;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosClosureValue;
import com.guillermomolina.protos.runtime.ProtosTask;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.source.Source;
import java.util.Objects;

public final class ProtosPerf006BytecodeTestSupport {
    private static final LanguageReference<ProtosLanguage> LANGUAGE_REF =
            LanguageReference.create(ProtosLanguage.class);

    private ProtosPerf006BytecodeTestSupport() {}

    public static final class Dependency implements ProtosTask.WaitDependency {
        private ProtosTask task;
        private boolean ready;

        public synchronized void register(ProtosTask owner) {
            Objects.requireNonNull(owner, "owner");
            if (task != null) {
                throw new IllegalStateException("dependency registered twice");
            }
            task = owner;
        }

        @Override
        public synchronized boolean isReady() {
            return ready;
        }

        public boolean complete() {
            ProtosTask owner;
            synchronized (this) {
                if (task == null) {
                    throw new IllegalStateException("dependency was never registered");
                }
                if (ready) {
                    throw new IllegalStateException("dependency completed twice");
                }
                ready = true;
                owner = task;
            }
            return owner.resume(this);
        }
    }

    public static ProtosClosureValue suspensionCapablePause(
            Dependency dependency,
            Object resumedValue) {
        Objects.requireNonNull(dependency, "dependency");
        Objects.requireNonNull(resumedValue, "resumedValue");
        return ProtosClosureValue.suspensionCapableNativeClosure(
                (activation, supplied) -> {
                    throw new AssertionError(
                            "C-prime integration used ordinary pause body");
                },
                (activation, supplied) -> {
                    if (!supplied.isEmpty()) {
                        throw new AssertionError("pause arity");
                    }
                    ProtosTask task =
                            activation.task()
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "pause requires owning Task"));
                    dependency.register(task);
                    return ProtosNativeSuspension.pending(
                            dependency,
                            () -> resumedValue);
                });
    }

    public static ProtosClosureValue parsedBytecodeClosure(
            String characters,
            String name,
            ProtosActivation activation) {
        Objects.requireNonNull(characters, "characters");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(activation, "activation");

        /*
         * B6A6A1 deliberately keeps parsePublic() on the AST/public path until B6B.
         * For this backend integration test, lower the exact characters through the
         * production Bytecode lowerer just as the retained B6A3B canonical-coverage
         * tests do, so the selected Actor handler is genuinely Bytecode/C-prime.
         */
        Source source =
                Source.newBuilder(ProtosLanguage.ID, characters, name)
                        .mimeType(ProtosLanguage.MIME_TYPE)
                        .build();
        CanonicalSequence canonical =
                (CanonicalSequence)
                        new Canonicalizer()
                                .canonicalize(
                                        new ProtosParser(
                                                        source.getCharacters()
                                                                .toString())
                                                .parseProgram());
        Object result =
                new CanonicalToBytecodeLowerer(
                                LANGUAGE_REF.get(null),
                                source,
                                // I101-A: shared helper; the entered Context owns the profile.
                                ProtosLanguageContext.current().executionMode())
                        .lowerRoot(canonical)
                        .getCallTarget()
                        .call(activation);
        if (!(result instanceof ProtosClosureValue closure)) {
            throw new AssertionError(
                    "expected Bytecode-lowered Closure, got "
                            + (result == null ? "null" : result.getClass().getName()));
        }
        if (closure.executionPlan().isEmpty()) {
            throw new AssertionError(
                    "explicit Bytecode lowering did not produce a Closure execution plan");
        }
        return closure;
    }
}
