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
 * @summary Crypto intrinsic array addresses must not overflow at large byte offsets
 * @requires vm.bits == "64"
 * @requires vm.gc.Serial
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -Xmx4g -XX:+UseSerialGC
 *      -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -XX:+UseJeandleCompiler
 *      -XX:-TieredCompilation -Xbatch -XX:-CreateCoredumpOnCrash
 *      --add-opens java.base/com.sun.crypto.provider=ALL-UNNAMED
 *      compiler.jeandle.intrinsic.TestCryptoLargeOffset
 */

package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestCryptoLargeOffset {
    public static void main(String[] args) throws Exception {
        WhiteBox wb = WhiteBox.getWhiteBox();
        if (!Boolean.TRUE.equals(wb.getBooleanVMFlag("UseAESCTRIntrinsics"))) {
            System.out.println("AES-CTR intrinsic unavailable; skipped");
            return;
        }
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding", "SunJCE");
        SecretKeySpec key = new SecretKeySpec(new byte[16], "AES");
        IvParameterSpec iv = new IvParameterSpec(new byte[16]);
        cipher.init(Cipher.ENCRYPT_MODE, key, iv);
        byte[] small = {37};
        byte expected = cipher.doFinal(small)[0];

        Method crypt = Class.forName("com.sun.crypto.provider.CounterMode")
                .getDeclaredMethod("crypt", byte[].class, int.class, int.class,
                        byte[].class, int.class);
        Asserts.assertTrue(wb.enqueueMethodForCompilation(crypt, 4),
                "must enqueue the AES-CTR intrinsic caller");
        Asserts.assertTrue(wb.isMethodCompiled(crypt),
                "AES-CTR caller must be installed before checking large offsets");

        byte[] large = new byte[Integer.MAX_VALUE - 8];
        int offset = large.length - 7;
        large[offset] = small[0];
        byte[] out = new byte[1];
        Asserts.assertEQ(cipher.doFinal(large, offset, 1, out, 0), 1);
        Asserts.assertEQ(out[0], expected, "large source offset");
        Asserts.assertEQ(cipher.doFinal(small, 0, 1, large, offset), 1);
        Asserts.assertEQ(large[offset], expected, "large destination offset");
        large[offset] = small[0];
        Asserts.assertEQ(cipher.doFinal(large, offset, 1, large, offset), 1);
        Asserts.assertEQ(large[offset], expected, "in-place large offset");
        Asserts.assertTrue(wb.isMethodCompiled(crypt),
                "large-offset checks must execute the installed intrinsic caller");
        System.out.println("TestCryptoLargeOffset PASSED");
    }
}
