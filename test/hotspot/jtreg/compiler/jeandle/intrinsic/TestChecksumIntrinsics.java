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
 * @summary Checksum intrinsic results, direct/heap addressing, and per-ID fallback
 * @requires os.arch == "amd64" | os.arch == "x86_64" | os.arch == "aarch64"
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.intrinsic.ChecksumBase64TestSupport
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.intrinsic.TestChecksumIntrinsics
 */

package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

import jdk.test.lib.Asserts;

public class TestChecksumIntrinsics {
    private static ChecksumBase64TestSupport.Entry entry(String id, String flag, String owner,
                                                        String method, String pattern) {
        return new ChecksumBase64TestSupport.Entry(id, flag, "java.util.zip." + owner, method,
                "java_util_zip_" + owner + "_" + switch (id) {
                    case "_updateCRC32" -> "update(I)V";
                    case "_updateBytesCRC32" -> "updateBytes(I[BII)I";
                    case "_updateByteBufferCRC32" -> "updateByteBuffer(IJII)I";
                    case "_updateBytesCRC32C", "_updateBytesAdler32" -> "update([BII)V";
                    default -> "update(Ljava/nio/ByteBuffer;)V";
                }, pattern);
    }

    public static void main(String[] args) throws Exception {
        ChecksumBase64TestSupport.run(Worker.class, List.of(
                entry("_updateCRC32", "UseCRC32Intrinsics", "CRC32", "update", "crc32.byte.value"),
                entry("_updateBytesCRC32", "UseCRC32Intrinsics", "CRC32", "updateBytes0",
                        "call[^\\n]*@StubRoutines_updateBytesCRC32\\("),
                entry("_updateByteBufferCRC32", "UseCRC32Intrinsics", "CRC32", "updateByteBuffer0",
                        "call[^\\n]*@StubRoutines_updateBytesCRC32_direct\\("),
                entry("_updateBytesCRC32C", "UseCRC32CIntrinsics", "CRC32C", "updateBytes",
                        "call[^\\n]*@StubRoutines_updateBytesCRC32C\\("),
                entry("_updateDirectByteBufferCRC32C", "UseCRC32CIntrinsics", "CRC32C", "updateDirectByteBuffer",
                        "call[^\\n]*@StubRoutines_updateBytesCRC32C_direct\\("),
                entry("_updateBytesAdler32", "UseAdler32Intrinsics", "Adler32", "updateBytes",
                        "call[^\\n]*@StubRoutines_updateBytesAdler32\\("),
                entry("_updateByteBufferAdler32", "UseAdler32Intrinsics", "Adler32", "updateByteBuffer",
                        "call[^\\n]*@StubRoutines_updateBytesAdler32_direct\\(")),
                "java/util/zip/CRC32.update*", "java/util/zip/CRC32C.update*",
                "java/util/zip/Adler32.update*");
    }

    public static class Worker {
        private static final int[] LENGTHS = {0, 1, 7, 8, 15, 16, 31, 32, 63, 64, 255, 256, 4096, 65536};
        private static long result;

        public static void main(String[] args) throws Exception {
            new CRC32().update(0);
            new CRC32C().update(new byte[8], 1, 7);
            new Adler32().update(new byte[8], 1, 7);
            // Load buffer implementations before installing CHA-dependent callers.
            for (ByteBuffer buffer : new ByteBuffer[]{ByteBuffer.allocate(8),
                    ByteBuffer.allocateDirect(8), ByteBuffer.allocateDirect(8).slice(),
                    ByteBuffer.allocateDirect(8).asReadOnlyBuffer()}) {
                new CRC32C().update(buffer.duplicate());
                new Adler32().update(buffer.duplicate());
            }
            List<Method> callers = List.of(
                    CRC32.class.getDeclaredMethod("update", int.class),
                    CRC32.class.getDeclaredMethod("updateBytes", int.class, byte[].class, int.class, int.class),
                    CRC32.class.getDeclaredMethod("updateByteBuffer", int.class, long.class, int.class, int.class),
                    CRC32C.class.getDeclaredMethod("update", byte[].class, int.class, int.class),
                    CRC32C.class.getDeclaredMethod("update", ByteBuffer.class),
                    Adler32.class.getDeclaredMethod("update", byte[].class, int.class, int.class),
                    Adler32.class.getDeclaredMethod("update", ByteBuffer.class));
            boolean compiled = args[0].equals("compiled");
            if (compiled) {
                ChecksumBase64TestSupport.compile(callers);
            }
            ChecksumBase64TestSupport.flags("UseCRC32Intrinsics", "UseCRC32CIntrinsics", "UseAdler32Intrinsics");
            for (int algorithm = 0; algorithm < 3; algorithm++) {
                byte[] known = "123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                Checksum checksum = create(algorithm);
                checksum.update(known, 0, known.length);
                Asserts.assertEQ(checksum.getValue(), new long[]{0xcbf43926L, 0xe3069283L, 0x091e01deL}[algorithm]);
                for (int length : LENGTHS) {
                    for (int offset : new int[]{0, 1, 19}) {
                        byte[] data = new byte[length + offset + 23];
                        for (int i = 0; i < data.length; i++) {
                            data[i] = (byte) (i * 37 + length * 11);
                        }
                        long expected = oracle(algorithm, data, offset, length);
                        checksum.reset();
                        checksum.update(data, offset, length);
                        record(checksum, expected);
                        checksum.reset();
                        int prefix = length / 3;
                        checksum.update(data, offset, prefix);
                        checksum.update(data, offset + prefix, length - prefix);
                        record(checksum, expected);
                        for (int kind = 0; kind < 4; kind++) {
                            ByteBuffer buffer = kind == 0 ? ByteBuffer.wrap(data)
                                    : ByteBuffer.allocateDirect(data.length).put(data).flip();
                            buffer.position(offset).limit(offset + length);
                            if (kind == 2) {
                                buffer = buffer.slice();
                            } else if (kind == 3) {
                                buffer = buffer.asReadOnlyBuffer();
                            }
                            int limit = buffer.limit();
                            checksum.reset();
                            checksum.update(buffer);
                            record(checksum, expected);
                            Asserts.assertEQ(buffer.position(), limit);
                            Asserts.assertEQ(buffer.limit(), limit);
                        }
                    }
                }
                checksum.reset();
                checksum.update(known, 0, 4);
                System.gc();
                checksum.update(known, 4, known.length - 4);
                record(checksum, oracle(algorithm, known, 0, known.length));
            }
            CRC32 byteCrc = new CRC32();
            byte[] bytes = new byte[1024];
            for (int i = 0; i < bytes.length; i++) {
                int value = i * 65537 - 512;
                bytes[i] = (byte) value;
                byteCrc.update(value);
                Asserts.assertEQ(byteCrc.getValue(), oracle(0, bytes, 0, i + 1));
            }
            result ^= byteCrc.getValue();
            System.gc();
            byteCrc.update(0x1234);
            byte[] afterGc = java.util.Arrays.copyOf(bytes, bytes.length + 1);
            afterGc[bytes.length] = 0x34;
            Asserts.assertEQ(byteCrc.getValue(), oracle(0, afterGc, 0, afterGc.length));
            if (compiled) {
                ChecksumBase64TestSupport.executed(callers);
            }
            for (int algorithm = 0; algorithm < 3; algorithm++) {
                Checksum c = create(algorithm);
                ChecksumBase64TestSupport.throwsType(NullPointerException.class, () -> c.update((byte[]) null, 0, 0));
                ChecksumBase64TestSupport.throwsType(NullPointerException.class, () -> c.update((ByteBuffer) null));
                ChecksumBase64TestSupport.throwsType(IndexOutOfBoundsException.class, () -> c.update(new byte[8], -1, 1));
                ChecksumBase64TestSupport.throwsType(IndexOutOfBoundsException.class, () -> c.update(new byte[8], 2, 7));
                ChecksumBase64TestSupport.throwsType(IndexOutOfBoundsException.class, () -> c.update(new byte[8], 0, -1));
            }
            System.out.println("RESULT " + Long.toUnsignedString(result));
        }

        private static Checksum create(int algorithm) {
            return switch (algorithm) {
                case 0 -> new CRC32();
                case 1 -> new CRC32C();
                default -> new Adler32();
            };
        }

        private static void record(Checksum checksum, long expected) {
            Asserts.assertEQ(checksum.getValue(), expected);
            result = result * 31 + checksum.getValue();
        }

        private static long oracle(int algorithm, byte[] data, int offset, int length) {
            if (algorithm == 2) {
                long a = 1, b = 0;
                for (int i = offset; i < offset + length; i++) {
                    a = (a + (data[i] & 255)) % 65521;
                    b = (b + a) % 65521;
                }
                return (b << 16) | a;
            }
            int crc = -1;
            int polynomial = algorithm == 0 ? 0xedb88320 : 0x82f63b78;
            for (int i = offset; i < offset + length; i++) {
                crc ^= data[i] & 255;
                for (int bit = 0; bit < 8; bit++) {
                    crc = (crc >>> 1) ^ ((crc & 1) == 0 ? 0 : polynomial);
                }
            }
            return Integer.toUnsignedLong(~crc);
        }
    }
}
