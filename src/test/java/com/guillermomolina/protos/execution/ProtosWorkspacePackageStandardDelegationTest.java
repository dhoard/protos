/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. See LICENSE.TXT.
 */
package com.guillermomolina.protos.execution;

import static org.junit.jupiter.api.Assertions.*;
import com.guillermomolina.protos.runtime.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ProtosWorkspacePackageStandardDelegationTest {
    private static final Path CORE = Path.of("protos","lib","core");
    private static final Path PROJECT = Path.of("src","test","resources","workspace-package-std");

    @Test void realProtosStdImportDelegatesExactly() throws Exception {
        RecordingStd std = new RecordingStd();
        ProtosWorkspacePackageModuleResolver r = new ProtosWorkspacePackageModuleResolver(PROJECT, plan(), std);
        ProtosModuleKey entry = r.entryModule("Main");
        ProtosPrelude prelude = new ProtosCoreBootstrap().bootstrap(CORE, r);
        ProtosObjectValue module = new ProtosModuleRuntime(r).loadCanonicalModule(entry, prelude.newModuleActivation());
        ProtosIntegerValue v=(ProtosIntegerValue)module.readLocalSlot("result").orElseThrow();
        assertEquals(Long.toString(88), com.guillermomolina.protos.runtime.ProtosNumericValueSupport.integerDecimalText(v));
        assertEquals("std:probe/Thing",std.specifier);
        assertEquals(Optional.of(entry),std.importer);
        assertEquals(1,std.resolveCalls.get()); assertEquals(1,std.loadCalls.get());
    }

    @Test void stdDomainAndClosureRemainExact() throws Exception {
        RecordingStd std = new RecordingStd();
        ProtosWorkspacePackageModuleResolver r = new ProtosWorkspacePackageModuleResolver(PROJECT, plan(), std);
        ProtosModuleKey entry=r.entryModule("Main");
        ProtosModuleKey key=r.resolve("std:probe/Thing",Optional.of(entry));
        assertEquals(new ProtosModuleKey("std:probe/Thing"),key);
        assertEquals("value: 88",r.loadSource(key).characters());
        assertThrows(IOException.class,()->r.resolve("probe/Thing",Optional.of(entry)));
        assertThrows(IOException.class,()->r.loadSource(new ProtosModuleKey("foreign:Thing")).characters());
    }

    @Test void packageOnlyConstructorKeepsStdClosedAndForeignDelegatedKeyFails() throws Exception {
        ProtosWorkspacePackageModuleResolver p = new ProtosWorkspacePackageModuleResolver(PROJECT, plan());
        ProtosModuleKey entry=p.entryModule("Main");
        assertThrows(Exception.class,()->p.resolve("std:probe/Thing",Optional.of(entry)));
        ProtosModuleResolver bad = new ProtosModuleResolver(){
            public ProtosModuleKey resolve(String s, Optional<ProtosModuleKey> i){return new ProtosModuleKey("foreign:Thing");}
            public ProtosModuleSource loadSource(ProtosModuleKey k){return ProtosModuleSource.fromCharacters(k, "value: 1");}
        };
        ProtosWorkspacePackageModuleResolver r=new ProtosWorkspacePackageModuleResolver(PROJECT,plan(),bad);
        assertThrows(IOException.class,()->r.resolve("std:probe/Thing",Optional.of(entry)));
    }

    private static ProtosPackageExecutionPlan plan(){
        var ref=new ProtosPackageExecutionPlan.WorkspaceRef("root-pkg");
        var node=new ProtosPackageExecutionPlan.PackageNode(ref,"",Map.of());
        return new ProtosPackageExecutionPlan(1,ref,List.of(node),List.of());
    }
    private static final class RecordingStd implements ProtosModuleResolver {
        AtomicInteger resolveCalls=new AtomicInteger(), loadCalls=new AtomicInteger(); String specifier; Optional<ProtosModuleKey> importer;
        public ProtosModuleKey resolve(String s, Optional<ProtosModuleKey> i) throws IOException {resolveCalls.incrementAndGet();specifier=s;importer=i;if(!s.equals("std:probe/Thing"))throw new IOException();return new ProtosModuleKey(s);}
        public ProtosModuleSource loadSource(ProtosModuleKey k) throws IOException {loadCalls.incrementAndGet();if(!k.equals(new ProtosModuleKey("std:probe/Thing")))throw new IOException();return ProtosModuleSource.fromCharacters(k, "value: 88");}
    }
}
