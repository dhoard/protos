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
package com.guillermomolina.protos.runtime;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Internal exact numeric hash key, not a guest Number or an identity.
 * Small hashes remain signed longs; a hash outside the signed-64 range keeps
 * the exact immutable payload of its large Integer, which is host data shared
 * by no guest object and therefore safe in keys copied across domains. No
 * numeric narrowing or collision-based equality.
 */
public final class ProtosNumericHashKey {
    private final long small;
    private final BigInteger large;

    private ProtosNumericHashKey(long small, BigInteger large) {
        this.small = small;
        this.large = large;
    }

    public static ProtosNumericHashKey ofLong(long value) {
        return new ProtosNumericHashKey(value, null);
    }

    /** Key of a value admitted by {@link ProtosNumericValueSupport#isCurrentInteger}. */
    public static ProtosNumericHashKey fromSemanticInteger(Object value) {
        Objects.requireNonNull(value, "value");
        // The numeric boundary owns guest representation recognition.
        // Hash keys retain only normalized inert host scalars, never a
        // guest Integer, prototype, Prelude or execution-domain state.
        Object scalar = ProtosNumericValueSupport.hostInteger(value);
        if (scalar instanceof Long small) {
            return ofLong(small.longValue());
        }
        return new ProtosNumericHashKey(0L, (BigInteger) scalar);
    }

    public static ProtosNumericHashKey fromIdentity(Object value) {
        return ofLong(ProtosIdentity.identityHash(value));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProtosNumericHashKey key)) {
            return false;
        }
        if (large == null || key.large == null) {
            return large == null && key.large == null
                    && small == key.small;
        }
        return large.equals(key.large);
    }

    @Override
    public int hashCode() {
        return large == null
                ? Long.hashCode(small)
                : large.hashCode();
    }
}
