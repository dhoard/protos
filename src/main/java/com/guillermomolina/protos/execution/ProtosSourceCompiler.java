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

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.parser.ast.SurfaceSequence;
import com.guillermomolina.protos.semantic.Canonicalizer;
import com.guillermomolina.protos.semantic.ast.CanonicalSequence;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.source.Source;
import java.util.Objects;

public final class ProtosSourceCompiler {
    private final Canonicalizer canonicalizer;

    public ProtosSourceCompiler() {
        this.canonicalizer = new Canonicalizer();
    }

    /**
     * I101-A (PLAT058-D): compiles {@code source} under the immutable physical compilation
     * {@code profile} of the Context that owns the resulting executable. The caller resolves that
     * profile at its language-entry boundary; the compiler never guesses it.
     */
    RootCallTarget compileBytecode(
            Source source, ProtosLanguage language, ProtosExecutionMode profile) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(profile, "profile");
        SurfaceSequence surface =
                new ProtosParser(source.getCharacters().toString()).parseProgram();
        CanonicalSequence canonical =
                (CanonicalSequence) canonicalizer.canonicalize(surface);
        return new CanonicalToBytecodeLowerer(language, source, profile)
                .lowerRoot(canonical)
                .getCallTarget();
    }

}
