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
 * You should have received a copy of the GNU General Public License
 * version 2 along with this work; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit our website at http://www.openjdk.org/ if you have any
 * additional questions or have any questions.
 */
package org.openjdk.bench.jeandle;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Benchmark for the crypto/digest intrinsics (MD5/SHA-256 multi-block
 * digests, AES ECB/CBC/CTR/GCM and ChaCha20) under Jeandle, compared
 * against C2 ({@code -XX:-UseJeandleCompiler} on the same build).
 *
 * Sizes bracket block counts: 64 is a single MD5/SHA-256/AES block, 1024
 * exercises the multi-block paths a few rounds deep, and 16384 keeps the
 * stub vector loops dominant (for GCM it is also above the ~7680-byte
 * threshold where the parallel GHASH path kicks in).
 *
 * JCE reuse rules shape the benchmark bodies. ECB/CBC/CTR ciphers may be
 * reused across doFinal calls, so those benchmarks time a bare doFinal.
 * GCM and ChaCha20 encryption must be re-initialized after every doFinal
 * (SunJCE throws IllegalStateException otherwise), and both additionally
 * reject a re-init that repeats the previous key+IV (nonce) pair, so
 * aesGcmEncrypt and chacha20Crypt rotate through preallocated parameter
 * specs over distinct windows of a source buffer and only turn the ring
 * index in the benchmark body. That init cost applies to both compilers
 * equally and is part of the measured op.
 *
 * NoPadding decryption needs no valid ciphertext: any block-multiple
 * byte string decrypts, so the decrypt benchmarks run on plain directly
 * and measure only the decrypt primitive.
 *
 * Jeandle replaces C2 in the tier-4 compiler slot, so an A/B is just the
 * -XX:+/-UseJeandleCompiler flag on otherwise identical runs:
 *
 *   make CONF=release test TEST='micro:org.openjdk.bench.jeandle.JeandleCryptoIntrinsics' \
 *       MICRO="VM_OPTIONS=-XX:-UseJeandleCompiler"
 *
 * Quick runs can subset with {@code -p size=1024} / {@code -f 1}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(value = 3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class JeandleCryptoIntrinsics {

    @Param({"16", "64", "1024", "16384", "65536"})
    int size;

    MessageDigest md5;
    MessageDigest sha1;
    MessageDigest sha256;
    MessageDigest sha512;
    MessageDigest sha3;

    SecretKeySpec aesKey;
    SecretKeySpec chachaKey;
    IvParameterSpec cbcIv;
    Cipher ecbEnc;
    Cipher ecbDec;
    Cipher cbcEnc;
    Cipher cbcDec;
    Cipher ctr;
    Cipher gcmEnc;
    Cipher gcmDec;
    Cipher chachaEnc;
    Cipher chachaStream;
    Cipher chachaAead;
    IvParameterSpec[] chachaAeadSpecs = new IvParameterSpec[CHACHA_WINDOWS];
    byte[][] gcmCiphertexts;

    // ChaCha20 re-init per op with a nonce that differs from the previous
    // init: 12 twelve-byte windows over a 24-byte source, preallocated (it
    // takes a ChaCha20ParameterSpec rather than an IvParameterSpec).
    private static final int CHACHA_WINDOWS = 12;
    ChaCha20ParameterSpec[] chachaSpecs = new ChaCha20ParameterSpec[CHACHA_WINDOWS];
    int chachaSpecIndex;

    // GCM re-init per op with a fresh key+IV pair: 16 sixteen-byte windows
    // over a 32-byte source (window i spans [i, i+16)), all preallocated so
    // the benchmark body only rotates the index.
    private static final int GCM_WINDOWS = 16;
    GCMParameterSpec[] gcmSpecs = new GCMParameterSpec[GCM_WINDOWS];
    int gcmSpecIndex;

    byte[] plain;
    byte[] out;

    @Setup
    public void setup() throws Exception {
        md5 = MessageDigest.getInstance("MD5", "SUN");
        sha1 = MessageDigest.getInstance("SHA-1", "SUN");
        sha256 = MessageDigest.getInstance("SHA-256", "SUN");
        sha512 = MessageDigest.getInstance("SHA-512", "SUN");
        sha3 = MessageDigest.getInstance("SHA3-256", "SUN");

        byte[] aesKeyBytes = new byte[16];
        for (int i = 0; i < aesKeyBytes.length; i++) {
            aesKeyBytes[i] = (byte) i;
        }
        aesKey = new SecretKeySpec(aesKeyBytes, "AES");

        byte[] chachaKeyBytes = new byte[32];
        for (int i = 0; i < chachaKeyBytes.length; i++) {
            chachaKeyBytes[i] = (byte) i;
        }
        chachaKey = new SecretKeySpec(chachaKeyBytes, "ChaCha20");

        byte[] iv = new byte[16];
        for (int i = 0; i < iv.length; i++) {
            iv[i] = (byte) i;
        }
        cbcIv = new IvParameterSpec(iv);

        byte[] chachaNonceSource = new byte[24];
        for (int i = 0; i < chachaNonceSource.length; i++) {
            chachaNonceSource[i] = (byte) (i * 41 + 7);
        }
        for (int i = 0; i < CHACHA_WINDOWS; i++) {
            chachaSpecs[i] = new ChaCha20ParameterSpec(
                    Arrays.copyOfRange(chachaNonceSource, i, i + 12), 1);
            chachaAeadSpecs[i] = new IvParameterSpec(chachaNonceSource, i, 12);
        }

        byte[] gcmIvSource = new byte[32];
        for (int i = 0; i < gcmIvSource.length; i++) {
            gcmIvSource[i] = (byte) (i * 37 + 11);
        }
        for (int i = 0; i < GCM_WINDOWS; i++) {
            gcmSpecs[i] = new GCMParameterSpec(128, gcmIvSource, i, 16);
        }

        plain = new byte[size];
        for (int i = 0; i < size; i++) {
            plain[i] = (byte) (i * 37 + 11);
        }
        // Largest output is GCM: ciphertext plus a 16-byte tag.
        out = new byte[size + 16];

        ecbEnc = Cipher.getInstance("AES/ECB/NoPadding", "SunJCE");
        ecbEnc.init(Cipher.ENCRYPT_MODE, aesKey);
        ecbDec = Cipher.getInstance("AES/ECB/NoPadding", "SunJCE");
        ecbDec.init(Cipher.DECRYPT_MODE, aesKey);
        cbcEnc = Cipher.getInstance("AES/CBC/NoPadding", "SunJCE");
        cbcEnc.init(Cipher.ENCRYPT_MODE, aesKey, cbcIv);
        cbcDec = Cipher.getInstance("AES/CBC/NoPadding", "SunJCE");
        cbcDec.init(Cipher.DECRYPT_MODE, aesKey, cbcIv);
        ctr = Cipher.getInstance("AES/CTR/NoPadding", "SunJCE");
        ctr.init(Cipher.ENCRYPT_MODE, aesKey, cbcIv);
        // gcmEnc and chachaEnc are initialized inside their benchmarks;
        // initializing here would just be thrown away on the first op.
        gcmEnc = Cipher.getInstance("AES/GCM/NoPadding", "SunJCE");
        gcmDec = Cipher.getInstance("AES/GCM/NoPadding", "SunJCE");
        gcmCiphertexts = new byte[GCM_WINDOWS][];
        Cipher prepare = Cipher.getInstance("AES/GCM/NoPadding", "SunJCE");
        for (int i = 0; i < GCM_WINDOWS; i++) {
            prepare.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpecs[i]);
            gcmCiphertexts[i] = prepare.doFinal(plain);
        }
        chachaEnc = Cipher.getInstance("ChaCha20", "SunJCE");
        chachaStream = Cipher.getInstance("ChaCha20", "SunJCE");
        chachaStream.init(Cipher.ENCRYPT_MODE, chachaKey, chachaSpecs[0]);
        chachaAead = Cipher.getInstance("ChaCha20-Poly1305", "SunJCE");
    }

    @Benchmark
    public byte[] digestMd5() {
        md5.reset();
        md5.update(plain, 0, plain.length);
        return md5.digest();
    }

    @Benchmark
    public byte[] digestSha256() {
        sha256.reset();
        sha256.update(plain, 0, plain.length);
        return sha256.digest();
    }

    @Benchmark
    public byte[] digestSha1() {
        return sha1.digest(plain);
    }

    @Benchmark
    public byte[] digestSha512() {
        return sha512.digest(plain);
    }

    @Benchmark
    public byte[] digestSha3() {
        return sha3.digest(plain);
    }

    @Benchmark
    public int aesEcbEncrypt() throws Exception {
        return ecbEnc.doFinal(plain, 0, plain.length, out, 0);
    }

    @Benchmark
    public int aesEcbDecrypt() throws Exception {
        return ecbDec.doFinal(plain, 0, plain.length, out, 0);
    }

    @Benchmark
    public int aesCbcEncrypt() throws Exception {
        return cbcEnc.doFinal(plain, 0, plain.length, out, 0);
    }

    @Benchmark
    public int aesCbcDecrypt() throws Exception {
        return cbcDec.doFinal(plain, 0, plain.length, out, 0);
    }

    @Benchmark
    public int aesCtrCrypt() throws Exception {
        return ctr.doFinal(plain, 0, plain.length, out, 0);
    }

    @Benchmark
    public int aesGcmEncrypt() throws Exception {
        GCMParameterSpec spec = gcmSpecs[gcmSpecIndex];
        gcmSpecIndex = (gcmSpecIndex + 1) % GCM_WINDOWS;
        gcmEnc.init(Cipher.ENCRYPT_MODE, aesKey, spec);
        return gcmEnc.doFinal(plain, 0, plain.length, out, 0);
    }

    @Benchmark
    public int chacha20Crypt() throws Exception {
        ChaCha20ParameterSpec spec = chachaSpecs[chachaSpecIndex];
        chachaSpecIndex = (chachaSpecIndex + 1) % CHACHA_WINDOWS;
        chachaEnc.init(Cipher.ENCRYPT_MODE, chachaKey, spec);
        return chachaEnc.doFinal(plain, 0, plain.length, out, 0);
    }

    @Benchmark
    public int aesGcmDecrypt() throws Exception {
        int index = gcmSpecIndex;
        gcmSpecIndex = (index + 1) % GCM_WINDOWS;
        gcmDec.init(Cipher.DECRYPT_MODE, aesKey, gcmSpecs[index]);
        byte[] ciphertext = gcmCiphertexts[index];
        return gcmDec.doFinal(ciphertext, 0, ciphertext.length, out, 0);
    }

    @Benchmark
    public int chacha20Update() throws Exception {
        return chachaStream.update(plain, 0, plain.length, out, 0);
    }

    @Benchmark
    public int chacha20Poly1305() throws Exception {
        IvParameterSpec spec = chachaAeadSpecs[chachaSpecIndex];
        chachaSpecIndex = (chachaSpecIndex + 1) % CHACHA_WINDOWS;
        chachaAead.init(Cipher.ENCRYPT_MODE, chachaKey, spec);
        return chachaAead.doFinal(plain, 0, plain.length, out, 0);
    }
}
