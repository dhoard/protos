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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosBytesValue;
import com.guillermomolina.protos.runtime.ProtosCoreErrors;
import com.guillermomolina.protos.runtime.ProtosEncodingValue;
import com.guillermomolina.protos.runtime.ProtosFloatValue;
import com.guillermomolina.protos.runtime.ProtosIntegerValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosPrelude;
import com.guillermomolina.protos.runtime.ProtosSignalException;
import com.guillermomolina.protos.runtime.ProtosStringValue;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ProtosStandardEncodingProtocolTest {
    private static final Path CORE = Path.of("protos", "lib", "core");

    // Deliberately Java-side: physical bootstrap representation, exact local-slot
    // inventory, internal portable descriptor kind, and represented parent are
    // implementation-owned checks rather than source-level conversion behavior.
    @Test
    void bootstrapExposesFrozenEncodingFactoryAndExactlyFourPortableDescriptors()
            throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosObjectValue encoding = prelude.encodingPrototype();

        assertSame(ProtosObjectValue.rootObject(), encoding.parent().orElseThrow());
        assertTrue(encoding.isFrozen());
        assertEquals(
                Set.of("UTF8", "UTF16LE", "UTF16BE", "Latin1", "encode", "decode"),
                encoding.localSlotsSnapshot().keySet());

        assertPortable(encoding, "UTF8", ProtosEncodingValue.PortableKind.UTF8);
        assertPortable(encoding, "UTF16LE", ProtosEncodingValue.PortableKind.UTF16LE);
        assertPortable(encoding, "UTF16BE", ProtosEncodingValue.PortableKind.UTF16BE);
        assertPortable(encoding, "Latin1", ProtosEncodingValue.PortableKind.LATIN1);
        assertFalse(encoding.hasLocalSlot("call"));
    }

    // I091: decode admits exactly the Integers 0..255 as octets; encode yields shared octets.
    @Test
    void decodeAdmitsOnlyExactOctetIntegers() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosActivation a = prelude.newModuleActivation();
        Object latin1 = descriptor(prelude, "Latin1");
        ProtosObjectValue bytesPrototype = bytesPrototype(prelude, a);

        assertEquals(
                "\u0000\u00ff",
                decode(latin1, bytes(bytesPrototype, new ProtosIntegerValue(0), new ProtosIntegerValue(255)), a));

        Object[] invalid = {
            new ProtosIntegerValue(-1),
            new ProtosIntegerValue(256),
            new ProtosIntegerValue((1L << 32) + 65),
            com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(BigInteger.ONE.shiftLeft(64), prelude),
            com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerFromHost(BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(65)), prelude),
            new ProtosFloatValue(65.0d),
        };
        for (Object bad : invalid) {
            ProtosBytesValue bytes = bytes(bytesPrototype, new ProtosIntegerValue(65), bad);
            ProtosSignalException signal =
                    assertThrows(
                            ProtosSignalException.class,
                            () -> ProtosInvocation.invokeMessage(latin1, "decode", List.of(bytes), a),
                            String.valueOf(bad));
            // A malformed octet is an argument Error, not a text conversion failure.
            assertSame(prelude.errorPrototype(), signal.error().parent().orElseThrow());
        }
    }

    @Test
    void portableEncodingsRoundTripAndMalformedTextIsAnEncodingError() throws Exception {
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE);
        ProtosActivation a = prelude.newModuleActivation();
        String text = "a\u00e9\uD83D\uDE00";
        for (String name : List.of("UTF8", "UTF16LE", "UTF16BE")) {
            Object encoding = descriptor(prelude, name);
            ProtosBytesValue encoded =
                    assertInstanceOf(
                            ProtosBytesValue.class,
                            ProtosInvocation.invokeMessage(
                                    encoding, "encode", List.of(new ProtosStringValue(text)), a));
            for (Object octet : encoded.indexedSnapshot()) {
                assertTrue((octet instanceof com.guillermomolina.protos.runtime.ProtosIntegerValue compact && compact.longValue() >= 0 && compact.longValue() <= 255), name);
            }
            assertEquals(text, decode(encoding, encoded, a), name);
        }
        ProtosBytesValue utf8 =
                (ProtosBytesValue)
                        ProtosInvocation.invokeMessage(
                                descriptor(prelude, "UTF8"), "encode", List.of(new ProtosStringValue("\uD83D\uDE00")), a);
        assertEquals(
                List.of("240", "159", "152", "128"),
                utf8.indexedSnapshot().stream().map(com.guillermomolina.protos.runtime.ProtosNumericValueSupport::integerDecimalText).toList());

        ProtosBytesValue malformed =
                bytes(bytesPrototype(prelude, a), new ProtosIntegerValue(0xc3), new ProtosIntegerValue(0x28));
        assertEncodingError(
                a, () -> ProtosInvocation.invokeMessage(descriptor(prelude, "UTF8"), "decode", List.of(malformed), a));
        assertEncodingError(
                a,
                () ->
                        ProtosInvocation.invokeMessage(
                                descriptor(prelude, "Latin1"), "encode", List.of(new ProtosStringValue("\u20ac")), a));
    }

    private static Object descriptor(ProtosPrelude prelude, String name) {
        return prelude.encodingPrototype().readLocalSlot(name).orElseThrow();
    }

    private static ProtosObjectValue bytesPrototype(ProtosPrelude prelude, ProtosActivation a) {
        Object encoded =
                ProtosInvocation.invokeMessage(
                        descriptor(prelude, "UTF8"), "encode", List.of(new ProtosStringValue("")), a);
        return (ProtosObjectValue) ((ProtosBytesValue) encoded).parent().orElseThrow();
    }

    private static ProtosBytesValue bytes(ProtosObjectValue prototype, Object... octets) {
        ProtosBytesValue bytes = new ProtosBytesValue(prototype);
        for (Object octet : octets) {
            bytes.indexedAdd(octet);
        }
        return bytes;
    }

    private static String decode(Object encoding, ProtosBytesValue bytes, ProtosActivation a) {
        return ((ProtosStringValue) ProtosInvocation.invokeMessage(encoding, "decode", List.of(bytes), a))
                .value();
    }

    private static void assertEncodingError(ProtosActivation a, Runnable operation) {
        ProtosSignalException signal = assertThrows(ProtosSignalException.class, operation::run);
        assertSame(
                ProtosCoreErrors.prototype(a, ProtosCoreErrors.StandardError.ENCODING_ERROR),
                signal.error().parent().orElseThrow());
    }

    private static void assertPortable(
            ProtosObjectValue encoding,
            String slot,
            ProtosEncodingValue.PortableKind kind) {
        ProtosEncodingValue descriptor =
                assertInstanceOf(
                        ProtosEncodingValue.class,
                        encoding.readLocalSlot(slot).orElseThrow());
        assertSame(encoding, descriptor.representedDelegationParent(null));
        assertTrue(descriptor.isPortableForRuntime());
        assertEquals(kind, descriptor.portableKindForRuntime());
    }

}
