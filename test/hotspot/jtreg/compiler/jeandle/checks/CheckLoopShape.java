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

package compiler.jeandle.checks;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.test.lib.Asserts;

final class CheckLoopShape {
    private static final Pattern LABEL = Pattern.compile("^([a-zA-Z0-9_.$-]+):");
    private static final Pattern EDGE = Pattern.compile("label %([a-zA-Z0-9_.$-]+)");

    private static Set<String> reachable(String start, Map<String, List<String>> edges) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> work = new ArrayDeque<>();
        work.add(start);
        while (!work.isEmpty()) {
            String block = work.removeFirst();
            if (visited.add(block)) work.addAll(edges.getOrDefault(block, List.of()));
        }
        return visited;
    }

    static void assertVersioned(Method method) throws Exception {
        assertVersioned(method, false);
    }

    static void assertVersioned(Method method, boolean osr) throws Exception {
        String prefix = method.getDeclaringClass().getName().replace('.', '_') + "_" + method.getName() + "_";
        List<Path> candidates;
        try (var paths = Files.list(Path.of(System.getProperty("user.dir")))) {
            candidates = paths.filter(p -> p.getFileName().toString().startsWith(prefix)
                    && p.getFileName().toString().endsWith("_optimized.ll"))
                    .sorted(Comparator.reverseOrder()).toList();
        }
        String ir = null;
        for (Path candidate : candidates) {
            String text = Files.readString(candidate);
            if (text.lines().anyMatch(line -> line.startsWith("define ") && line.contains(".root") && line.contains("__jeandle_osr") == osr)) {
                ir = text;
                break;
            }
        }
        Asserts.assertNotNull(ir, (osr ? "OSR" : "ordinary-entry") + " optimized IR for " + method.getName());
        Map<String, List<String>> blocks = new HashMap<>();
        Map<String, List<String>> edges = new HashMap<>();
        String block = null;
        for (String line : ir.split("\n")) {
            Matcher label = LABEL.matcher(line);
            if (label.find()) {
                block = label.group(1);
                blocks.put(block, new ArrayList<>());
                edges.put(block, new ArrayList<>());
            } else if (block != null) {
                blocks.get(block).add(line.trim());
                Matcher edge = EDGE.matcher(line);
                while (edge.find()) edges.get(block).add(edge.group(1));
            }
        }
        for (String guard : blocks.keySet()) {
            if (!guard.contains(".affine.check")) continue;
            List<String> successors = edges.get(guard);
            if (successors.size() != 2 || !successors.get(1).contains(".affine.slow")) continue;
            Set<String> fast = reachable(successors.get(0), edges);
            Set<String> slow = reachable(successors.get(1), edges);
            Set<String> fastOnly = new HashSet<>(fast);
            fastOnly.removeAll(slow);
            Set<String> slowOnly = new HashSet<>(slow);
            slowOnly.removeAll(fast);
            Asserts.assertTrue(fastOnly.stream().flatMap(b -> blocks.get(b).stream())
                    .anyMatch(line -> line.contains("!llvm.loop")), "fast region contains a loop");
            Asserts.assertFalse(fastOnly.stream().flatMap(b -> blocks.get(b).stream())
                    .anyMatch(line -> line.startsWith("br i1 ")
                            && (line.contains("boundary_check_fail") || line.contains("null_check_fail"))),
                    "fast loop has no array-check failure branch");
            Asserts.assertTrue(slowOnly.stream().flatMap(b -> blocks.get(b).stream())
                    .anyMatch(line -> line.startsWith("br i1 ")
                            && (line.contains("boundary_check_fail") || line.contains("null_check_fail"))),
                    "slow loop retains array-check failure branch");
            return;
        }
        throw new AssertionError("missing guarded fast/slow loops for " + method.getName());
    }
}
