/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary Compile replay of a Case-C synthetic object with a dead predecessor slot must not abort in PEA
 * @library /test/lib
 * @requires os.family == "linux"
 * @requires vm.compMode != "Xint"
 * @run main/othervm compiler.jeandle.pea.TestPEAReplayDeadPhiEdge
 */

package compiler.jeandle.pea;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

/**
 * Replays a minimized ciReplay file whose compile record contains a Case-C
 * synthetic virtual object with a dead PHI predecessor edge. The
 * corresponding slot of {@code SyntheticSourceIDs} keeps the
 * {@code InvalidObjectID} sentinel (~0u). PEA used to insert that sentinel
 * into a {@code DenseSet<ObjectID>} before filtering it, which is the
 * DenseMap empty key and aborts assertion-enabled builds:
 *
 *   Assertion `!KeyInfoT::isEqual(Val, EmptyKey) && ...
 *   && "Empty/Tombstone value shouldn't be inserted into map!"' failed.
 */
public class TestPEAReplayDeadPhiEdge {
    private static final String REPLAY_FILE_NAME = "replay_dead_phi_edge.log";
    private static final long TIMEOUT_MINUTES = 5;

    public static void main(String[] args) throws Exception {
        Path replayFile = Path.of(System.getProperty("test.src"), REPLAY_FILE_NAME)
                .toAbsolutePath();
        if (!Files.isReadable(replayFile)) {
            throw new RuntimeException("Replay file is not readable: " + replayFile);
        }

        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+ReplayCompiles",
                "-XX:ReplayDataFile=" + replayFile,
                "--version");

        Process process = pb.start();
        OutputAnalyzer output = new OutputAnalyzer(process);
        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly().waitFor();
            output.waitFor();
            throw new RuntimeException("Replay VM did not exit within "
                    + TIMEOUT_MINUTES + " minutes, output:" + System.lineSeparator()
                    + output.getOutput());
        }

        output.shouldHaveExitValue(0);
    }
}
