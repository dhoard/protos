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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guillermomolina.protos.runtime.ProtosActivation;
import com.guillermomolina.protos.runtime.ProtosLexicalFallback;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ProtosI026EScopeTest {
    private final InteropLibrary interop = InteropLibrary.getUncached();

    @Test
    void debuggerScopeExposesExactActivationLanguageAndDisplayIdentity()
            throws Exception {
        ProtosStringValue value = new ProtosStringValue("current");
        ProtosObjectValue context = objectWith("current", value);
        ProtosActivation activation =
                new ProtosActivation(
                        context,
                        List.of(),
                        ProtosObjectValue.rootObject());

        Object scope = new ProtosDebuggerScope(activation);

        assertTrue(interop.isScope(scope));
        assertTrue(interop.hasMembers(scope));
        assertTrue(interop.hasLanguageId(scope));
        assertEquals(ProtosLanguage.ID, interop.getLanguageId(scope));
        assertSame(value, interop.readMember(scope, "current"));
        assertEquals("scope", interop.toDisplayString(scope, false));
    }

    @Test
    void flattenedNamesPreserveLookupPrecedenceAndShadowingDuplicates()
            throws Exception {
        ProtosStringValue currentShadow =
                new ProtosStringValue("current-shadow");
        ProtosStringValue lexicalOneShadow =
                new ProtosStringValue("lexical-one-shadow");
        ProtosStringValue lexicalTwoShadow =
                new ProtosStringValue("lexical-two-shadow");
        ProtosStringValue receiverShadow =
                new ProtosStringValue("receiver-shadow");
        ProtosStringValue parentShadow =
                new ProtosStringValue("parent-shadow");

        ProtosObjectValue receiverParent =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        receiverParent.createLocalSlot(
                "parentOnly",
                new ProtosStringValue("parent"));
        receiverParent.createLocalSlot("shadow", parentShadow);

        ProtosObjectValue receiver =
                new ProtosObjectValue(receiverParent);
        receiver.createLocalSlot(
                "receiverOnly",
                new ProtosStringValue("receiver"));
        receiver.createLocalSlot("shadow", receiverShadow);

        ProtosObjectValue lexicalOne =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        lexicalOne.createLocalSlot(
                "lexicalOneOnly",
                new ProtosStringValue("lexical-one"));
        lexicalOne.createLocalSlot("shadow", lexicalOneShadow);

        ProtosObjectValue lexicalTwo =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        lexicalTwo.createLocalSlot(
                "lexicalTwoOnly",
                new ProtosStringValue("lexical-two"));
        lexicalTwo.createLocalSlot("shadow", lexicalTwoShadow);

        ProtosObjectValue context =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        context.createLocalSlot(
                "currentOnly",
                new ProtosStringValue("current"));
        context.createLocalSlot("shadow", currentShadow);

        ProtosActivation activation =
                new ProtosActivation(
                        context,
                        List.of(lexicalOne, lexicalTwo),
                        receiver);

        Object scope = new ProtosDebuggerScope(activation);

        List<String> names = memberNames(scope);
        assertTrue(names.size() >= 10);
        assertEquals(
                List.of(
                        "currentOnly", "shadow",
                        "lexicalOneOnly", "shadow",
                        "lexicalTwoOnly", "shadow",
                        "receiverOnly", "shadow",
                        "parentOnly", "shadow"),
                names.subList(0, 10));

        assertSame(
                currentShadow,
                ProtosLexicalFallback.readByName(activation, "shadow").orElseThrow());
        assertSame(
                currentShadow,
                interop.readMember(scope, "shadow"));
        assertEquals(
                "parent",
                interop.asString(
                        interop.readMember(scope, "parentOnly")));
    }

    @Test
    void scopeReadUsesOrdinaryReceiverDelegationWithoutLexicalParentFiction()
            throws Exception {
        ProtosStringValue inherited =
                new ProtosStringValue("inherited");

        ProtosObjectValue receiverParent =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        receiverParent.createLocalSlot("inherited", inherited);

        ProtosObjectValue receiver =
                new ProtosObjectValue(receiverParent);

        ProtosActivation activation =
                new ProtosActivation(
                        new ProtosObjectValue(
                                ProtosObjectValue.rootObject()),
                        List.of(),
                        receiver);

        Object scope = new ProtosDebuggerScope(activation);

        assertSame(
                ProtosLexicalFallback.readByName(activation, "inherited").orElseThrow(),
                interop.readMember(scope, "inherited"));

        assertFalse(interop.hasScopeParent(scope));
        assertThrows(
                UnsupportedMessageException.class,
                () -> interop.getScopeParent(scope));
    }

    @Test
    void scopeIsReadOnly() throws Exception {
        ProtosObjectValue context =
                objectWith(
                        "name",
                        new ProtosStringValue("value"));

        ProtosActivation activation =
                new ProtosActivation(
                        context,
                        List.of(),
                        ProtosObjectValue.rootObject());

        Object scope = new ProtosDebuggerScope(activation);

        assertFalse(interop.isMemberModifiable(scope, "name"));
        assertFalse(interop.isMemberInsertable(scope, "newName"));
        assertFalse(interop.isMemberRemovable(scope, "name"));

        assertThrows(
                UnsupportedMessageException.class,
                () ->
                        interop.writeMember(
                                scope,
                                "name",
                                new ProtosStringValue("replacement")));

        assertSame(
                ProtosLexicalFallback.readByName(activation, "name").orElseThrow(),
                interop.readMember(scope, "name"));
    }

    @Test
    void accidentalHostOnlyValueNeverLeavesTheScope()
            throws Exception {
        ProtosObjectValue context =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        context.createLocalSlot("hostOnly", new Object());

        ProtosActivation activation =
                new ProtosActivation(
                        context,
                        List.of(),
                        ProtosObjectValue.rootObject());

        Object scope = new ProtosDebuggerScope(activation);

        assertTrue(memberNames(scope).contains("hostOnly"));
        assertFalse(interop.isMemberReadable(scope, "hostOnly"));
        assertThrows(
                UnknownIdentifierException.class,
                () -> interop.readMember(scope, "hostOnly"));
    }

    @Test
    void noArtificialLanguageTopScopeOrGlobalScopeRegistryIsIntroduced() {
        // PLAT054-3 ratifies the standard host binding scope through Truffle getScope. It is not a
        // global registry: it is only offered by standard-embedding Contexts, and driver-owned
        // Contexts still expose no language top scope (see ProtosI026EDebuggerIntegrationTest).
        assertTrue(
                Arrays.stream(ProtosLanguage.class.getDeclaredMethods())
                        .anyMatch(method -> method.getName().equals("getScope")));

        List<java.lang.reflect.Field> instanceFields =
                Arrays.stream(
                                ProtosDebuggerScope.class
                                        .getDeclaredFields())
                        .filter(
                                field ->
                                        !Modifier.isStatic(
                                                field.getModifiers()))
                        .toList();

        assertEquals(
                2,
                instanceFields.size(),
                "scope retains its Activation and one virtual callback binding snapshot");

        assertTrue(instanceFields.stream().allMatch(
                field -> Modifier.isFinal(field.getModifiers())),
                "scope must not acquire mutable registry or frame references");
        assertTrue(instanceFields.stream().anyMatch(
                field -> field.getType() == ProtosActivation.class));
        assertTrue(instanceFields.stream().anyMatch(
                field -> field.getName().equals("inlineCallbackBindings")
                        && field.getType() == java.util.Map.class));
        assertTrue(Arrays.stream(ProtosDebuggerScope.class.getDeclaredFields())
                .noneMatch(field -> Modifier.isStatic(field.getModifiers())
                        && java.util.Map.class.isAssignableFrom(field.getType())),
                "no global scope binding registry is allowed");
    }

    private List<String> memberNames(Object scope) throws Exception {
        Object members = interop.getMembers(scope);
        long size = interop.getArraySize(members);
        ArrayList<String> result =
                new ArrayList<>((int) size);

        for (long i = 0; i < size; i++) {
            result.add(
                    interop.asString(
                            interop.readArrayElement(members, i)));
        }

        return List.copyOf(result);
    }

    private static ProtosObjectValue objectWith(
            String name,
            Object value) {
        ProtosObjectValue object =
                new ProtosObjectValue(ProtosObjectValue.rootObject());
        object.createLocalSlot(name, value);
        return object;
    }
}
