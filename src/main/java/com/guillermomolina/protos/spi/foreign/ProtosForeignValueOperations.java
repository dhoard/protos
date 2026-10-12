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
package com.guillermomolina.protos.spi.foreign;

import java.util.List;

/**
 * The single value contract of one external provider (I085-A, D188): classification of its
 * opaque handles and the foreign operations Protos may perform on them.
 *
 * <p>Every operation receives the live session that produced the handle and runs only after
 * Protos checked its preconditions (session generation, capability, argument kinds). Any thrown
 * exception is an entered foreign failure and becomes a fresh {@code ForeignError} described only
 * by {@link #describeFailure}. Defaults describe a provider with no capability beyond
 * classification. Nothing here grants host authority.
 */
public interface ProtosForeignValueOperations {
    /** Safe name of the foreign language or library, used for {@code ForeignError.language}. */
    String language();

    /** Classifies one handle by its source semantic category. */
    ProtosForeignValueClass classify(ProtosForeignPluginSession session, Object handle)
            throws Exception;

    /**
     * Opts into Long for outbound signed-64 Integer arguments.
     * The default preserves the existing BigInteger provider ABI.
     */
    default boolean supportsLongIntegerArguments() {
        return false;
    }

    /** Whether the provider represents {@code argument} losslessly; checked before entry. */
    default boolean acceptsArgument(ProtosForeignArgumentValue argument) {
        return true;
    }

    /**
     * Whether reading member {@code name} is faithful to an ordinary Protos member read: no
     * material side effects, and receiver binding of a method-like result preserved.
     */
    default boolean canFaithfullyReadMember(
            ProtosForeignPluginSession session, Object handle, String name) throws Exception {
        return false;
    }

    default Object readMember(ProtosForeignPluginSession session, Object handle, String name)
            throws Exception {
        throw new UnsupportedOperationException("foreign member read");
    }

    default Object execute(
            ProtosForeignPluginSession session,
            Object handle,
            List<ProtosForeignArgumentValue> arguments)
            throws Exception {
        throw new UnsupportedOperationException("foreign execution");
    }

    default Object instantiate(
            ProtosForeignPluginSession session,
            Object handle,
            List<ProtosForeignArgumentValue> arguments)
            throws Exception {
        throw new UnsupportedOperationException("foreign instantiation");
    }

    default void writeMember(
            ProtosForeignPluginSession session,
            Object handle,
            String name,
            ProtosForeignArgumentValue value)
            throws Exception {
        throw new UnsupportedOperationException("foreign member write");
    }

    default Object readElement(
            ProtosForeignPluginSession session, Object handle, ProtosForeignArgumentValue index)
            throws Exception {
        throw new UnsupportedOperationException("foreign element read");
    }

    default void writeElement(
            ProtosForeignPluginSession session,
            Object handle,
            ProtosForeignArgumentValue index,
            ProtosForeignArgumentValue value)
            throws Exception {
        throw new UnsupportedOperationException("foreign element write");
    }

    /** A fresh provider-private iterator; it is never classified or guest-visible. */
    default Object openIterator(ProtosForeignPluginSession session, Object handle)
            throws Exception {
        throw new UnsupportedOperationException("foreign iterator");
    }

    default boolean iteratorHasNext(ProtosForeignPluginSession session, Object iterator)
            throws Exception {
        throw new UnsupportedOperationException("foreign iterator has-next");
    }

    default Object iteratorNext(ProtosForeignPluginSession session, Object iterator)
            throws Exception {
        throw new UnsupportedOperationException("foreign iterator next");
    }

    /** Sanitizes one entered failure; the default exposes nothing. */
    default ProtosForeignFailureInfo describeFailure(Throwable failure) {
        return ProtosForeignFailureInfo.NONE;
    }
}
