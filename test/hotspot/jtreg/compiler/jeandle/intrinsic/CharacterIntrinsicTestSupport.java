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
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package compiler.jeandle.intrinsic;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import jdk.test.lib.process.OutputAnalyzer;

final class CharacterIntrinsicTestSupport {
    private static final String FLAG_PREFIX = "SpecialEncodeISOArray=";

    static void configureChild(List<String> options) {
        // The test classes share the java.base patch with the intrinsic caller.
        options.addAll(0, List.of("--add-reads=java.base=java.management",
                "--add-reads=java.base=jdk.management"));
        if (Boolean.getBoolean("test.disableEncodingIntrinsics")) {
            options.add(0, "-XX:-SpecialEncodeISOArray");
        }
    }

    static boolean reportEncodingSupport() {
        var bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        boolean enabled = Boolean.parseBoolean(bean.getVMOption("SpecialEncodeISOArray").getValue());
        System.out.println(FLAG_PREFIX + enabled);
        return enabled;
    }

    static boolean encodingEnabled(OutputAnalyzer output) {
        String value = output.firstMatch("(?m)^" + FLAG_PREFIX + "(true|false)$", 1);
        if (value == null) throw new AssertionError("Missing child encoding capability");
        return Boolean.parseBoolean(value);
    }
}
