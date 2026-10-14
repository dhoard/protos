#!/usr/bin/env python3
# THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
# ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
# DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION OF
# DERIVATIVE WORKS BASED ON, THE LICENSED WORK CONSTITUTES RECIPIENT'S ACCEPTANCE
# OF THIS LICENSE AND ITS TERMS, WHETHER OR NOT SUCH RECIPIENT READS THE TERMS
# OF THE LICENSE. "LICENSED WORK" AND "RECIPIENT" ARE DEFINED IN THE LICENSE.
# A COPY OF THE LICENSE IS LOCATED IN THE TEXT FILE ENTITLED "LICENSE.TXT"
# ACCOMPANYING THE CONTENTS OF THIS FILE. IF A COPY OF THE LICENSE DOES NOT
# ACCOMPANY THE CONTENTS OF THIS FILE, A COPY OF THE LICENSE MAY ALSO BE
# OBTAINED AT THE FOLLOWING WEB SITE:
# https://github.com/guillermomolina/protos
#
# Software distributed under the License is distributed on an "AS IS" basis,
# WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
# for the specific language governing rights and limitations under the License.
"""Fast unit tests of I101 graph-capture evidence classification (no Maven/Graal)."""

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import i101_local_binding_graph as graph  # noqa: E402

TARGET = "protos-root:0123456789abcdef[CLOSURE|file:///t/literal.protos|0+12]"


def success_log(tier=1, scenario="literal"):
    return "\n".join([
        "[engine] opt done " + TARGET + " |Tier " + str(tier),
        "I101_LOCAL_GRAPH_CASE=" + scenario,
        "I101_LOCAL_GRAPH_TARGET=" + TARGET,
        "I101_LOCAL_GRAPH_TIER=" + str(tier),
        "I101_LOCAL_GRAPH_RESULT=1",
        "I101_LOCAL_GRAPH_BYTECODE=" + str(Path("/tmp/sig")),
        "I101_LOCAL_GRAPH_GROUP_ROOTS=3",
        "I101_LOCAL_GRAPH_COMPILED=YES",
    ])


class I101LocalGraphTests(unittest.TestCase):
    def test_three_way_source_capture_has_six_isolated_lanes(self):
        self.assertEqual(
            ("literal", "create_only", "create_and_read"), graph.SCENARIOS)
        self.assertEqual((1, 2), graph.TIERS)

    def test_options_do_not_silently_collapse_compiler_tiers(self):
        for scenario in graph.SCENARIOS:
            for tier in graph.TIERS:
                command = graph.lane_command()
                flags = graph.lane_options(scenario, tier, Path("target/dump"))
                self.assertNotIn("CompileImmediately", str(flags))
                self.assertNotIn("-DargLine", str(command))
                self.assertIn("-Dpolyglot.engine.BackgroundCompilation=false", flags)
                self.assertIn(graph.TRUFFLE_COMPILER_EXPORT, flags)
                self.assertIn("-Dprotos.i101.graphTier=" + str(tier), flags)
                env = graph.lane_environment(scenario, tier, Path("target/dump"))
                self.assertIn("-Dprotos.i101.graphTier=" + str(tier), env["JAVA_TOOL_OPTIONS"])
                self.assertIn(graph.TRUFFLE_COMPILER_EXPORT, env["JAVA_TOOL_OPTIONS"])

    def test_parse_rejects_missing_and_conflicting_evidence(self):
        self.assertEqual(graph.parse_markers(success_log())["COMPILED"], "YES")
        with self.assertRaisesRegex(ValueError, "CONFLICTING_MARKER"):
            graph.parse_markers(success_log() + "\nI101_LOCAL_GRAPH_TIER=2")
        with self.assertRaisesRegex(ValueError, "MARKERS_MISSING_OR_UNKNOWN"):
            graph.parse_markers(success_log().replace("I101_LOCAL_GRAPH_RESULT=1", ""))

    def test_capture_requires_selected_bgv_and_opt_done(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            dump = base / "graphs"
            dump.mkdir()
            signature = base / "signature"
            signature.write_text("load.constant\nreturn\n", encoding="utf-8")
            log = success_log().replace("/tmp/sig", str(signature))
            empty = graph.validate_lane(log, 0, "literal", 1, dump, signature)
            self.assertIn("SELECTED_ROOT_NO_BGV", empty["problems"])
            (dump / (TARGET.split("[", 1)[0] + "-1.bgv")).write_bytes(b"BIGV123")
            good = graph.validate_lane(log, 0, "literal", 1, dump, signature)
            self.assertEqual("CAPTURE_READY", good["status"])
            (dump / (TARGET.split("[", 1)[0] + "-phase2.bgv")).write_bytes(b"BIGV456")
            multiple = graph.validate_lane(log, 0, "literal", 1, dump, signature)
            self.assertEqual("CAPTURE_READY", multiple["status"])
            self.assertEqual(2, len(multiple["selected_bgv"]))
            missing_trace = graph.validate_lane(log.replace("opt done", "opt start"), 0,
                                                "literal", 1, dump, signature)
            self.assertIn("SELECTED_ROOT_NO_OPT_DONE", missing_trace["problems"])
            wrong_tier = graph.validate_lane(log, 0, "literal", 2, dump, signature)
            self.assertIn("WRONG_TIER", wrong_tier["problems"])

    def test_build_failure_cannot_be_disguised_as_compilation_success(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            signature = base / "signature"
            signature.write_text("return\n", encoding="utf-8")
            dump = base / "dumps"
            dump.mkdir()
            (dump / (TARGET.split("[", 1)[0] + "-1.bgv")).write_bytes(b"BIGV123")
            log = success_log().replace("/tmp/sig", str(signature))
            result = graph.validate_lane(log, 1, "literal", 1, dump, signature)
            self.assertEqual("ACQUISITION_FAILED", result["status"])
            self.assertIn("MAVEN_RETURN_CODE=1", result["problems"])


if __name__ == "__main__":
    unittest.main()
