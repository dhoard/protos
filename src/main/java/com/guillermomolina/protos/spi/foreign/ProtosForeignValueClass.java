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

import java.math.BigInteger;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-declared source semantic classification of one foreign handle (D188).
 *
 * <p>Protos converts only the scalar kinds, and only as declared: a provider reports {@link
 * Kind#INTEGER} or {@link Kind#BINARY64} for values whose source family is integral or binary
 * floating, never because the bits happen to fit, and {@link Kind#NULL} only for a true absence.
 * Every other value is a {@link Kind#HANDLE}: an opaque reference carrying the provider's stable
 * identity key, if any, and the capabilities Protos may use.
 */
public final class ProtosForeignValueClass {
    public enum Kind {
        BOOLEAN,
        NULL,
        STRING,
        INTEGER,
        BINARY64,
        HANDLE
    }

    /** Foreign capabilities a handle offers to the Protos projection; never guest-visible. */
    public enum Capability {
        EXECUTABLE,
        INSTANTIABLE,
        INDEXED_READ,
        INDEXED_WRITE,
        /** Ordinary pull iteration is a faithful, unambiguous projection of this handle. */
        ITERABLE,
        /** The explicit {@code std:interop} member-read family is supported. */
        MEMBER_READ,
        /** The explicit {@code std:interop} member-write family is supported. */
        MEMBER_WRITE
    }

    private final Kind kind;
    private final Object scalar;
    private final Object identityKey;
    private final Set<Capability> capabilities;

    private ProtosForeignValueClass(
            Kind kind, Object scalar, Object identityKey, Set<Capability> capabilities) {
        this.kind = kind;
        this.scalar = scalar;
        this.identityKey = identityKey;
        this.capabilities = capabilities;
    }

    public static ProtosForeignValueClass absent() {
        return new ProtosForeignValueClass(Kind.NULL, null, null, Set.of());
    }

    public static ProtosForeignValueClass booleanValue(boolean value) {
        return new ProtosForeignValueClass(Kind.BOOLEAN, value, null, Set.of());
    }

    public static ProtosForeignValueClass text(String value) {
        return new ProtosForeignValueClass(
                Kind.STRING, Objects.requireNonNull(value, "value"), null, Set.of());
    }

    /** Signed-64 source Integer without arbitrary-precision allocation. */
    public static ProtosForeignValueClass integral(long value) {
        return new ProtosForeignValueClass(Kind.INTEGER, value, null, Set.of());
    }

    public static ProtosForeignValueClass integral(BigInteger value) {
        return new ProtosForeignValueClass(
                Kind.INTEGER, Objects.requireNonNull(value, "value"), null, Set.of());
    }

    public static ProtosForeignValueClass binary64(double value) {
        return new ProtosForeignValueClass(Kind.BINARY64, value, null, Set.of());
    }

    /**
     * @param identityKey provider-defined key whose equality is the stable foreign identity, or
     *     null when the handle has none
     */
    public static ProtosForeignValueClass handle(
            Object identityKey, Set<Capability> capabilities) {
        Set<Capability> copy = EnumSet.noneOf(Capability.class);
        copy.addAll(Objects.requireNonNull(capabilities, "capabilities"));
        return new ProtosForeignValueClass(Kind.HANDLE, null, identityKey, Set.copyOf(copy));
    }

    public Kind kind() {
        return kind;
    }

    /** The scalar of a non-handle kind: Boolean, String, Long, BigInteger, Double, or null. */
    public Object scalar() {
        return scalar;
    }

    public Object identityKey() {
        return identityKey;
    }

    public Set<Capability> capabilities() {
        return capabilities;
    }
}
