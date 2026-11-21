/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 */

/*
 * @test
 * @summary Load reference signature classes before requesting Jeandle compilation
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+UseJeandleCompiler -XX:-TieredCompilation -Xbatch -XX:CompileThreshold=100
 *      -XX:CompileCommand=compileonly,TestSignatureClassLoading::signatureOnly
 *      TestSignatureClassLoading
 */

import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestSignatureClassLoading {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();

    public static void main(String[] args) throws Exception {
        Asserts.assertFalse(WB.isClassAlive("SignatureArgument"));
        Asserts.assertFalse(WB.isClassAlive("SignatureResult"));
        for (int i = 0; i < 20_000; i++) {
            signatureOnly(null);
        }
        // Looking up the method reflectively before this check would itself
        // load its signature classes, hiding missing compilation preparation.
        Asserts.assertTrue(WB.isClassAlive("SignatureArgument"), "argument class loaded");
        Asserts.assertTrue(WB.isClassAlive("SignatureResult"), "return class loaded");
        Asserts.assertEQ(System.getProperty("SignatureArgument.initialized"), null);
        Asserts.assertEQ(System.getProperty("SignatureResult.initialized"), null);
        Method target = TestSignatureClassLoading.class.getDeclaredMethod(
                "signatureOnly", SignatureArgument.class);
        Asserts.assertTrue(WB.isMethodCompiled(target), "target was compiled");
        Asserts.assertEQ(WB.getMethodCompilationLevel(target), 4);
        Asserts.assertEQ(signatureOnly(null), null);
        Asserts.assertTrue(WB.isMethodCompiled(target), "target remained compiled");
        System.out.println("SIGNATURE_CLASSES loaded without initialization; compiled target executed");
    }

    private static SignatureResult signatureOnly(SignatureArgument argument) {
        return null;
    }
}

class SignatureArgument {
    static {
        System.setProperty("SignatureArgument.initialized", "true");
    }
}

class SignatureResult {
    static {
        System.setProperty("SignatureResult.initialized", "true");
    }
}
