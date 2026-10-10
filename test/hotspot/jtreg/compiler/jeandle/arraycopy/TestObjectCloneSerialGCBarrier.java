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
 *
 */

/*
 * @test
 * @summary Verify that bulk instance clone records old-to-young references
 *          with SerialGC when ReduceInitialCardMarks is disabled.
 * @requires vm.gc.Serial
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver TestObjectCloneSerialGCBarrier
 */

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestObjectCloneSerialGCBarrier {
    private static final String CHILD = "child";
    private static final String PASS = "OBJECT_CLONE_SERIAL_GC_BARRIER_PASS";
    private static final String INTRINSIC_LOG =
            "Method `virtual jobject java.lang.Object.clone()` is parsed as intrinsic";

    // These roots deliberately outlive createFixture(). Only cloneRoot is a
    // strong path to the Marker; weakRoot lets the test detect missed scanning
    // without first dereferencing a potentially stale oop.
    private static CloneTarget cloneRoot;
    private static WeakReference<Marker> weakRoot;
    private static byte[] allocationGapRoot;

    private static final class WhiteBoxHolder {
        private static final WhiteBox WB = WhiteBox.getWhiteBox();
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && CHILD.equals(args[0])) {
            runWorkload();
            return;
        }

        runCase(false);
        if (!Boolean.getBoolean("test.c2.only")) {
            runCase(true);
        }
    }

    private static void runCase(boolean useJeandle) throws Exception {
        String copyMethod = CloneTarget.class.getName() + "::copy";
        ArrayList<String> command = new ArrayList<>(List.of(
                "-Xbootclasspath/a:.",
                "-Xms64m", "-Xmx64m",
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation",
                "-XX:-BackgroundCompilation",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+IgnoreUnrecognizedVMOptions",
                "-XX:+WhiteBoxAPI",
                "-XX:+UseSerialGC",
                "-XX:-ReduceInitialCardMarks",
                "-XX:-UseTLAB",
                "-XX:PretenureSizeThreshold=64",
                useJeandle
                        ? "-XX:+UseJeandleCompiler"
                        : "-XX:-UseJeandleCompiler",
                "-XX:CompileCommand=compileonly," + copyMethod,
                "-XX:CompileCommand=dontinline," + copyMethod,
                "-XX:+PrintCompilation",
                "-Xlog:gc=debug"));

        if (useJeandle) {
            command.add("-Xlog:jeandle=debug,jit+compilation=debug,gc=debug");
            command.add("-XX:+CIPrintCompilerName");
        }
        command.add(TestObjectCloneSerialGCBarrier.class.getName());
        command.add(CHILD);

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0)
                .shouldContain(PASS)
                .shouldContain("SOURCE_OLD=true")
                .shouldContain("CLONE_OLD=true")
                .shouldContain("REFERENT_OLD=false")
                .shouldContain(CloneTarget.class.getName() + "::copy");

        if (useJeandle) {
            output.shouldContain(INTRINSIC_LOG);
        } else {
            output.shouldNotContain(INTRINSIC_LOG);
        }
    }

    private static void runWorkload() {
        WhiteBox wb = WhiteBoxHolder.WB;
        createFixture();

        // createFixture() has returned, so neither the original object nor its
        // local Marker variable can keep the referent alive at this safepoint.
        wb.youngGC();

        Marker marker = weakRoot.get();
        Asserts.assertNotNull(marker,
                "young referent was not discovered through the old clone");
        cloneRoot.assertReferences(marker);
        Asserts.assertEQ(marker.value, 0x5a17c0de,
                "referent contents after young GC");
        System.out.println(PASS);
    }

    private static void createFixture() {
        WhiteBox wb = WhiteBoxHolder.WB;
        Marker marker = new Marker(0x5a17c0de);
        CloneTarget source = new CloneTarget(marker);

        boolean sourceOld = wb.isObjectInOldGen(source);
        boolean referentOld = wb.isObjectInOldGen(marker);
        System.out.println("SOURCE_OLD=" + sourceOld);
        System.out.println("REFERENT_OLD=" + referentOld);
        Asserts.assertTrue(sourceOld,
                "PretenureSizeThreshold must place the source in old gen");
        Asserts.assertFalse(referentOld,
                "the small Marker must initially be in young gen");

        // Keep source and clone on different cards. The source's constructor
        // dirties its own card, which would otherwise mask a missing clone
        // barrier because SerialGC scans dirty cards without old-gen liveness.
        allocationGapRoot = new byte[4096];
        Asserts.assertTrue(wb.isObjectInOldGen(allocationGapRoot),
                "allocation gap must be pretenured");

        CloneTarget clone = source.copy();
        clone.assertReferences(marker);
        boolean cloneOld = wb.isObjectInOldGen(clone);
        System.out.println("CLONE_OLD=" + cloneOld);
        Asserts.assertTrue(cloneOld,
                "PretenureSizeThreshold must place the clone in old gen");
        Asserts.assertFalse(wb.isObjectInOldGen(marker),
                "clone setup must not trigger a young collection");

        weakRoot = new WeakReference<>(marker);
        cloneRoot = clone;

        // Remove the source's strong edges before its frame disappears. Its
        // dirty card remains harmless, while the clone must be found through
        // the post barrier emitted for the bulk copy itself.
        source.clearReferences();
    }

    static final class Marker {
        final int value;

        Marker(int value) {
            this.value = value;
        }
    }

    // Sixteen reference fields exceed ArrayCopyLoadStoreMaxElem (8), forcing
    // C2 and Jeandle to retain the bulk clone operation instead of scalarizing
    // the copy into individually barriered field stores.
    static final class CloneTarget implements Cloneable {
        Object r00;
        Object r01;
        Object r02;
        Object r03;
        Object r04;
        Object r05;
        Object r06;
        Object r07;
        Object r08;
        Object r09;
        Object r10;
        Object r11;
        Object r12;
        Object r13;
        Object r14;
        Object r15;

        CloneTarget(Object value) {
            r00 = value;
            r01 = value;
            r02 = value;
            r03 = value;
            r04 = value;
            r05 = value;
            r06 = value;
            r07 = value;
            r08 = value;
            r09 = value;
            r10 = value;
            r11 = value;
            r12 = value;
            r13 = value;
            r14 = value;
            r15 = value;
        }

        CloneTarget copy() {
            try {
                return (CloneTarget) super.clone();
            } catch (CloneNotSupportedException exception) {
                throw new AssertionError(exception);
            }
        }

        void clearReferences() {
            r00 = null;
            r01 = null;
            r02 = null;
            r03 = null;
            r04 = null;
            r05 = null;
            r06 = null;
            r07 = null;
            r08 = null;
            r09 = null;
            r10 = null;
            r11 = null;
            r12 = null;
            r13 = null;
            r14 = null;
            r15 = null;
        }

        void assertReferences(Object expected) {
            Asserts.assertSame(r00, expected, "r00");
            Asserts.assertSame(r01, expected, "r01");
            Asserts.assertSame(r02, expected, "r02");
            Asserts.assertSame(r03, expected, "r03");
            Asserts.assertSame(r04, expected, "r04");
            Asserts.assertSame(r05, expected, "r05");
            Asserts.assertSame(r06, expected, "r06");
            Asserts.assertSame(r07, expected, "r07");
            Asserts.assertSame(r08, expected, "r08");
            Asserts.assertSame(r09, expected, "r09");
            Asserts.assertSame(r10, expected, "r10");
            Asserts.assertSame(r11, expected, "r11");
            Asserts.assertSame(r12, expected, "r12");
            Asserts.assertSame(r13, expected, "r13");
            Asserts.assertSame(r14, expected, "r14");
            Asserts.assertSame(r15, expected, "r15");
        }
    }
}
