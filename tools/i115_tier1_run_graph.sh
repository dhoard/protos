#!/usr/bin/env bash
# THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
# ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
# DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION OF
# DERIVATIVE WORKS BASED ON, THE LICENSED WORK CONSTITUTES RECIPIENT'S ACCEPTANCE
# OF THIS LICENSE AND ITS TERMS, WHETHER OR NOT SUCH RECIPIENT READ THE TERMS OF
# THE LICENSE. "LICENSED WORK" AND "RECIPIENT" ARE DEFINED IN THE LICENSE. A COPY
# OF THE LICENSE IS LOCATED IN THE TEXT FILE ENTITLED "LICENSE.TXT" ACCOMPANYING
# THE CONTENTS OF THIS FILE. IF A COPY OF THE LICENSE DOES NOT ACCOMPANY THE
# CONTENTS OF THIS FILE, A COPY OF THE LICENSE MAY ALSO BE OBTAINED AT THE
# FOLLOWING WEB SITE: https://github.com/guillermomolina/protos
#
# Software distributed under the License is distributed on an "AS IS" basis,
# WITHOUT WARRANTY OF ANY KIND, either express or implied. See LICENSE.TXT.

# One Maven invocation, one real run() Tier 1 compilation, one newly generated
# BGV and one structural assertion. No benchmark repository or cached summary.
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$ROOT/target/truffle-compilation/i115-run"
DUMP=$(mktemp -d "$ROOT/target/truffle-compilation/i115-run/graphs-XXXXXX")

echo "I115_TIER1_DUMP_PATH=$DUMP"
cd "$ROOT" &&
exec env JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} \
--add-modules=jdk.graal.compiler \
--add-exports=jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED \
--add-exports=jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED \
-Dpolyglot.engine.AllowExperimentalOptions=true \
-Dpolyglot.engine.BackgroundCompilation=false \
-Dpolyglot.engine.TraceCompilation=true \
-Dpolyglot.engine.MultiTier=true \
-Dpolyglot.engine.DynamicCompilationThresholds=false \
-Dpolyglot.engine.FirstTierMinInvokeThreshold=1 \
-Dpolyglot.engine.MinInvokeThreshold=1 \
-Dpolyglot.engine.OSR=false \
-Dpolyglot.engine.FirstTierCompilationThreshold=100 \
-Dpolyglot.engine.LastTierCompilationThreshold=1000000 \
-Dpolyglot.engine.CompilationFailureAction=Throw \
-Dprotos.diagnostic.stableRootIdentity=true \
-Dprotos.i115.tier1GraphCapture=true \
-Djdk.graal.Dump=Truffle:1 \
-Djdk.graal.PrintGraph=File \
-Djdk.graal.DumpPath=$DUMP" \
mvn -Dtest=ProtosI115Tier1RunGraphTest test
