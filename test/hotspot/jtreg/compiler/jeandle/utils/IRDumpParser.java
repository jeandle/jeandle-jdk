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

package compiler.jeandle.utils;

import jdk.test.lib.Asserts;

import java.util.ArrayList;
import java.util.List;

/**
 * Slices {@code -print-before=-print-after=<pass>} IR dumps and
 * {@code -debug-only} trace out of a child JVM's combined output.
 *
 * Pass dumps appear on stderr as sections bracketed by banners of the form
 *   ; *** IR Dump Before <PassClass> on <mangled-fn> ***
 *   ; *** IR Dump After  <PassClass> on <mangled-fn> ***
 * A pass whose pipeline name is shared by several modes (e.g.
 * safepoint-poll-elimination<early;after-strip-mining;loop-deletion-prep>)
 * dumps one section pair per run in pipeline order, all with the same class
 * name, so runs are distinguished by section index.
 *
 * Jeandle trace lines ({@code -debug-only=<debug-type>}) are anchored by
 * per-run headers of the form "<debug-text> running on <mangled-fn>".
 */
public class IRDumpParser {

    /** Textual IR of one explicit safepoint poll (matches the call, not the declare). */
    public static final String POLL_CALL = "call hotspotcc void @jeandle.safepoint_poll()";

    /** Number of "IR Dump <phase> <passClass>" banners whose function name
     *  contains methodSuffix. */
    public static int countSections(String out, String phase, String passClass, String methodSuffix) {
        return extractSections(out, phase, passClass, methodSuffix).size();
    }

    /** Bodies of all matching "IR Dump <phase> <passClass>" sections in
     *  pipeline order. */
    public static List<String> extractSections(String out, String phase, String passClass,
                                               String methodSuffix) {
        String header = "IR Dump " + phase + " " + passClass;
        List<String> sections = new ArrayList<>();
        StringBuilder section = null;
        for (String line : out.split("\\n")) {
            if (line.contains(header) && line.contains(methodSuffix)) {
                if (section != null) {
                    sections.add(section.toString());
                }
                section = new StringBuilder();
                continue;
            }
            if (section != null && line.contains("*** IR Dump ")) {
                sections.add(section.toString());
                section = null;
                continue;
            }
            if (section != null) {
                section.append(line).append("\n");
            }
        }
        if (section != null) {
            sections.add(section.toString());
        }
        return sections;
    }

    /** Body of the Nth (0-based) "IR Dump <phase> <passClass>" section whose
     *  function name contains methodSuffix, up to the next dump banner. */
    public static String extractNthSection(String out, String phase, String passClass,
                                           String methodSuffix, int n) {
        List<String> sections = extractSections(out, phase, passClass, methodSuffix);
        return n >= 0 && n < sections.size() ? sections.get(n) : "";
    }

    /** Trace lines of one compiled method: from its first "running on" header
     *  up to the next method's "running on" header. All pass runs of one
     *  method are consecutive in the stream, so the chunk covers every mode.
     *  methodSuffix must uniquely identify the function (e.g. "TestX_method"). */
    public static List<String> extractTraceChunk(String out, String methodSuffix) {
        List<String> chunk = new ArrayList<>();
        boolean inChunk = false;
        for (String line : out.split("\\n")) {
            if (line.contains("running on")) {
                if (line.contains(methodSuffix)) {
                    inChunk = true;
                } else if (inChunk) {
                    break;
                }
            }
            if (inChunk) {
                chunk.add(line);
            }
        }
        return chunk;
    }

    public static boolean traceChunkContains(String out, String methodSuffix, String needle) {
        for (String line : extractTraceChunk(out, methodSuffix)) {
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public static int countOccurrences(String text, String needle) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** Number of explicit safepoint poll calls in an IR section. */
    public static int countPolls(String irSection) {
        return countOccurrences(irSection, POLL_CALL);
    }

    public static void assertContains(String text, String needle, String msg) {
        if (!text.contains(needle)) {
            Asserts.fail(msg + " -- expected to find: " + needle);
        }
    }

    public static void assertNotContains(String text, String needle, String msg) {
        if (text.contains(needle)) {
            Asserts.fail(msg + " -- expected NOT to find: " + needle);
        }
    }
}
