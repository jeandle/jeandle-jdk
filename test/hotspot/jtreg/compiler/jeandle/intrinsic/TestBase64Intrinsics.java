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
 * @summary Base64 intrinsic modes, tails, aliasing, errors, and per-ID fallback
 * @requires os.arch == "amd64" | os.arch == "x86_64" | os.arch == "aarch64"
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.intrinsic.ChecksumBase64TestSupport
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.intrinsic.TestBase64Intrinsics
 */

package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

public class TestBase64Intrinsics {
    public static void main(String[] args) throws Exception {
        ChecksumBase64TestSupport.run(Worker.class, List.of(
                new ChecksumBase64TestSupport.Entry("_base64_encodeBlock", "UseBASE64Intrinsics",
                        "java.util.Base64$Encoder", "encodeBlock", "java_util_Base64$Encoder_encode0([BII[B)I",
                        "call[^\\n]*@StubRoutines_base64_encodeBlock\\("),
                new ChecksumBase64TestSupport.Entry("_base64_decodeBlock", "UseBASE64Intrinsics",
                        "java.util.Base64$Decoder", "decodeBlock", "java_util_Base64$Decoder_decode0([BII[B)I",
                        "call[^\\n]*@StubRoutines_base64_decodeBlock\\(")),
                "java/util/Base64$Encoder.encode0", "java/util/Base64$Decoder.decode0");
    }

    public static class Worker {
        private static final int[] LENGTHS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 15, 16, 23, 24, 25, 29, 30, 31, 32, 33, 34, 35, 36,
                47, 48, 63, 64, 75, 76, 77, 255, 256, 4096, 65536};
        private static long result;

        private enum BufferKind {
            HEAP, HEAP_SLICE, READ_ONLY_HEAP, DIRECT, READ_ONLY_DIRECT
        }

        public static void main(String[] args) throws Exception {
            Base64.Encoder[] encoders = {Base64.getEncoder(), Base64.getUrlEncoder(), Base64.getMimeEncoder()};
            Base64.Decoder[] decoders = {Base64.getDecoder(), Base64.getUrlDecoder(), Base64.getMimeDecoder()};
            List<Method> callers = List.of(
                    Base64.Encoder.class.getDeclaredMethod("encode0", byte[].class, int.class, int.class, byte[].class),
                    Base64.Decoder.class.getDeclaredMethod("decode0", byte[].class, int.class, int.class, byte[].class));
            boolean compiled = args[0].equals("compiled");
            if (compiled) {
                ChecksumBase64TestSupport.compile(callers);
            }
            ChecksumBase64TestSupport.flags("UseBASE64Intrinsics");
            String[] plain = {"", "f", "fo", "foo", "foob", "fooba", "foobar"};
            String[] encoded = {"", "Zg==", "Zm8=", "Zm9v", "Zm9vYg==", "Zm9vYmE=", "Zm9vYmFy"};
            for (int i = 0; i < plain.length; i++) {
                Asserts.assertEQ(encoders[0].encodeToString(plain[i].getBytes(StandardCharsets.US_ASCII)), encoded[i]);
                Asserts.assertEQ(new String(decoders[0].decode(encoded[i]), StandardCharsets.US_ASCII), plain[i]);
            }
            for (int mode = 0; mode < encoders.length; mode++) {
                for (boolean padding : new boolean[]{true, false}) {
                    Base64.Encoder encoder = padding ? encoders[mode] : encoders[mode].withoutPadding();
                    Base64.Decoder decoder = decoders[mode];
                    for (int length : LENGTHS) {
                        byte[] data = new byte[length];
                        for (int i = 0; i < length; i++) {
                            data[i] = (byte) (i * 37 + length * 11);
                        }
                        byte[] text = encoder.encode(data);
                        consume(text);
                        Asserts.assertTrue(Arrays.equals(decoder.decode(text), data));
                        byte[] output = new byte[text.length + 17];
                        Arrays.fill(output, (byte) 0x5a);
                        Asserts.assertEQ(encoder.encode(data, output), text.length);
                        Asserts.assertTrue(Arrays.equals(Arrays.copyOf(output, text.length), text));
                        tail(output, text.length);
                        byte[] decoded = new byte[length + 17];
                        Arrays.fill(decoded, (byte) 0x5a);
                        Asserts.assertEQ(decoder.decode(text, decoded), length);
                        Asserts.assertTrue(Arrays.equals(Arrays.copyOf(decoded, length), data));
                        tail(decoded, length);
                        byte[] inPlace = text.clone();
                        Asserts.assertEQ(decoder.decode(inPlace, inPlace), length);
                        Asserts.assertTrue(Arrays.equals(Arrays.copyOf(inPlace, length), data));
                        for (BufferKind kind : BufferKind.values()) {
                            ByteBuffer source = buffer(data, kind);
                            int limit = source.limit();
                            ByteBuffer bytes = encoder.encode(source);
                            Asserts.assertEQ(source.position(), limit);
                            Asserts.assertEQ(source.limit(), limit);
                            byte[] actual = new byte[bytes.remaining()];
                            bytes.get(actual);
                            Asserts.assertTrue(Arrays.equals(actual, text));
                            ByteBuffer input = buffer(text, kind);
                            limit = input.limit();
                            ByteBuffer restored = decoder.decode(input);
                            Asserts.assertEQ(input.position(), limit);
                            Asserts.assertEQ(input.limit(), limit);
                            byte[] actualData = new byte[restored.remaining()];
                            restored.get(actualData);
                            Asserts.assertTrue(Arrays.equals(actualData, data));
                        }
                    }
                }
            }
            // MIME decoding must resume after a block stops at a non-alphabet byte.
            byte[] noise = "Z m\r\n9v!Y m\tFy".getBytes(StandardCharsets.US_ASCII);
            Asserts.assertEQ(new String(decoders[2].decode(noise), StandardCharsets.US_ASCII), "foobar");
            for (int length : new int[]{7, 31, 32, 33, 34, 36, 256, 4096}) {
                byte[] data = new byte[length];
                for (int i = 0; i < length; i++) {
                    data[i] = (byte) (i * 37 + length);
                }
                byte[] clean = encoders[0].encode(data);
                for (int stride : new int[]{1, 3, 4, 13, 76}) {
                    java.io.ByteArrayOutputStream decorated = new java.io.ByteArrayOutputStream();
                    for (int i = 0; i < clean.length; i++) {
                        decorated.write(clean[i]);
                        if (clean[i] != '=' && (i + 1) % stride == 0) {
                            decorated.write('\r');
                            decorated.write('\n');
                            decorated.write(0xff);
                            decorated.write('!');
                        }
                    }
                    byte[] input = decorated.toByteArray();
                    byte[] output = new byte[length + 17];
                    Arrays.fill(output, (byte) 0x5a);
                    Asserts.assertEQ(decoders[2].decode(input, output), length);
                    Asserts.assertTrue(Arrays.equals(Arrays.copyOf(output, length), data));
                    tail(output, length);
                    byte[] inPlace = input.clone();
                    Asserts.assertEQ(decoders[2].decode(inPlace, inPlace), length);
                    Asserts.assertTrue(Arrays.equals(Arrays.copyOf(inPlace, length), data));
                    consume(output);
                }
            }
            System.gc();
            Asserts.assertEQ(encoders[1].encodeToString(new byte[]{(byte) 0xfb, (byte) 0xff, (byte) 0xff}), "-___");
            if (compiled) {
                ChecksumBase64TestSupport.executed(callers);
            }
            internalPadding(decoders, callers.get(1), compiled);
            for (Base64.Decoder decoder : decoders) {
                for (String invalid : new String[]{"A", "=AAA", "A===", "AA=A", "Zm9vYg=A"}) {
                    ChecksumBase64TestSupport.throwsType(IllegalArgumentException.class, () -> decoder.decode(invalid));
                }
                ChecksumBase64TestSupport.throwsType(NullPointerException.class, () -> decoder.decode((byte[]) null));
                ChecksumBase64TestSupport.throwsType(IllegalArgumentException.class,
                        () -> decoder.decode("Zm9vYmFy".getBytes(StandardCharsets.US_ASCII), new byte[5]));
            }
            for (Base64.Decoder decoder : new Base64.Decoder[]{decoders[0], decoders[1]}) {
                for (int bad : new int[]{'!', 0x80, 0xff}) {
                    byte[] input = encoders[0].encode(new byte[96]);
                    input[40] = (byte) bad;
                    ChecksumBase64TestSupport.throwsType(IllegalArgumentException.class, () -> decoder.decode(input));
                }
            }
            for (Base64.Encoder encoder : encoders) {
                ChecksumBase64TestSupport.throwsType(NullPointerException.class, () -> encoder.encode((byte[]) null));
                ChecksumBase64TestSupport.throwsType(IllegalArgumentException.class,
                        () -> encoder.encode(new byte[4], new byte[3]));
            }
            System.out.println("RESULT " + Long.toUnsignedString(result));
        }

        private static ByteBuffer buffer(byte[] bytes, BufferKind kind) {
            boolean direct = kind == BufferKind.DIRECT || kind == BufferKind.READ_ONLY_DIRECT;
            ByteBuffer buffer = direct ? ByteBuffer.allocateDirect(bytes.length + 24)
                    : ByteBuffer.allocate(bytes.length + 24);
            if (kind == BufferKind.HEAP_SLICE) {
                buffer.position(11);
                buffer = buffer.slice();
                Asserts.assertEQ(buffer.arrayOffset(), 11);
            }
            buffer.position(7).put(bytes).flip().position(7);
            if (kind == BufferKind.READ_ONLY_HEAP || kind == BufferKind.READ_ONLY_DIRECT) {
                buffer = buffer.asReadOnlyBuffer();
            }
            Asserts.assertEQ(buffer.hasArray(), kind == BufferKind.HEAP || kind == BufferKind.HEAP_SLICE);
            return buffer;
        }

        private static void internalPadding(Base64.Decoder[] decoders, Method caller, boolean compiled) {
            for (int mode = 0; mode < decoders.length; mode++) {
                Base64.Decoder decoder = decoders[mode];
                for (int end : new int[]{32, 64, 256}) {
                    for (int padding : new int[]{1, 2}) {
                        byte[] input = ("A".repeat(end - padding) + "=".repeat(padding) + "AAAA")
                                .getBytes(StandardCharsets.US_ASCII);
                        for (int api = 0; api < 5; api++) {
                            if (compiled) {
                                // An expected exception may deoptimize the caller before the next case.
                                WhiteBox wb = WhiteBox.getWhiteBox();
                                if (!wb.isMethodCompiled(caller)) {
                                    ChecksumBase64TestSupport.compile(List.of(caller));
                                }
                                Asserts.assertTrue(wb.isMethodCompiled(caller), "compiled padding caller");
                                Asserts.assertEQ(wb.getMethodCompilationLevel(caller), 4);
                            }
                            System.out.println("PADDING mode=" + mode + " end=" + end
                                    + " padding=" + padding + " api=" + api + " compiled=" + compiled);
                            int operation = api;
                            ChecksumBase64TestSupport.throwsType(IllegalArgumentException.class, () -> {
                                switch (operation) {
                                    case 0 -> decoder.decode(input, new byte[input.length]);
                                    case 1 -> decoder.decode(input);
                                    case 2 -> {
                                        byte[] inPlace = input.clone();
                                        decoder.decode(inPlace, inPlace);
                                    }
                                    case 3 -> decoder.decode(buffer(input, BufferKind.HEAP));
                                    case 4 -> decoder.decode(buffer(input, BufferKind.READ_ONLY_HEAP));
                                    default -> throw new AssertionError(operation);
                                }
                            });
                        }
                    }
                }
            }
        }

        private static void consume(byte[] bytes) {
            for (byte value : bytes) {
                result = result * 31 + (value & 255);
            }
        }

        private static void tail(byte[] bytes, int start) {
            for (int i = start; i < bytes.length; i++) {
                Asserts.assertEQ(bytes[i], (byte) 0x5a, "bytes beyond returned length");
            }
        }
    }
}
