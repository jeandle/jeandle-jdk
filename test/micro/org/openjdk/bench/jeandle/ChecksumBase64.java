/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 */

package org.openjdk.bench.jeandle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class ChecksumBase64 {
    @State(Scope.Thread)
    public static class Inputs {
        @Param({"1", "31", "256", "4096", "65536"})
        public int length;
        public byte[] data;
        public ByteBuffer direct;
        public CRC32 crc32;
        public CRC32C crc32c;
        public Adler32 adler32;
        public int nextByte;

        @Setup(Level.Trial)
        public void setup() {
            JeandleBenchmarkSupport.requireJavaBlackhole();
            data = new byte[length + 19];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i * 37 + length * 11);
            }
            direct = ByteBuffer.allocateDirect(data.length).put(data).flip();
            direct.limit(length + 7).position(7);
            crc32 = new CRC32();
            crc32c = new CRC32C();
            adler32 = new Adler32();
        }
    }

    @State(Scope.Thread)
    public static class TextInputs {
        @Param({"1", "23", "24", "25", "29", "30", "31", "32", "33", "34", "35", "36", "256", "4096", "65536"})
        public int length;
        @Param({"basic", "url", "mime"})
        public String mode;
        public byte[] data;
        public byte[] encoded;
        public byte[] encodeOutput;
        public byte[] decodeOutput;
        public Base64.Encoder encoder;
        public Base64.Decoder decoder;

        @Setup(Level.Trial)
        public void setup() {
            JeandleBenchmarkSupport.requireJavaBlackhole();
            data = new byte[length];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i * 37 + length * 11);
            }
            encoder = switch (mode) {
                case "url" -> Base64.getUrlEncoder().withoutPadding();
                case "mime" -> Base64.getMimeEncoder();
                default -> Base64.getEncoder();
            };
            decoder = switch (mode) {
                case "url" -> Base64.getUrlDecoder();
                case "mime" -> Base64.getMimeDecoder();
                default -> Base64.getDecoder();
            };
            encoded = encoder.encode(data);
            encodeOutput = new byte[encoded.length];
            decodeOutput = new byte[data.length];
        }
    }

    @State(Scope.Thread)
    public static class CompressionInputs {
        @Param({"1", "31", "256", "4096", "65536"})
        public int length;
        @Param({"periodic", "random"})
        public String dataPattern;
        public byte[] data;

        @Setup(Level.Trial)
        public void setup() {
            JeandleBenchmarkSupport.requireJavaBlackhole();
            data = new byte[length + 7];
            if (dataPattern.equals("random")) {
                new Random(675).nextBytes(data);
            } else {
                for (int i = 0; i < data.length; i++) {
                    data[i] = (byte) (i * 37 + length * 11);
                }
            }
        }
    }

    @State(Scope.Thread)
    public static class MimeInputs {
        @Param({"31", "4096", "65536"})
        public int length;
        @Param({"bare", "crlf", "noise"})
        public String separator;
        public byte[] encoded;
        public byte[] output;
        public Base64.Decoder decoder;

        @Setup(Level.Trial)
        public void setup() {
            JeandleBenchmarkSupport.requireJavaBlackhole();
            byte[] data = new byte[length];
            for (int i = 0; i < length; i++) {
                data[i] = (byte) (i * 37 + length * 11);
            }
            byte[] clean = Base64.getEncoder().encode(data);
            if (separator.equals("crlf")) {
                encoded = Base64.getMimeEncoder().encode(data);
            } else if (separator.equals("noise")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                for (int i = 0; i < clean.length; i++) {
                    bytes.write(clean[i]);
                    if (clean[i] != '=' && (i + 1) % 13 == 0) {
                        bytes.write('\t');
                        bytes.write(0xff);
                        bytes.write('!');
                    }
                }
                encoded = bytes.toByteArray();
            } else {
                encoded = clean;
            }
            output = new byte[length];
            decoder = Base64.getMimeDecoder();
        }
    }

    @Benchmark
    public long crc32Byte(Inputs input) {
        input.crc32.update(input.nextByte++);
        return input.crc32.getValue();
    }

    @Benchmark
    public long crc32Array(Inputs input) {
        input.crc32.reset();
        input.crc32.update(input.data, 7, input.length);
        return input.crc32.getValue();
    }

    @Benchmark
    public long crc32cArray(Inputs input) {
        input.crc32c.reset();
        input.crc32c.update(input.data, 7, input.length);
        return input.crc32c.getValue();
    }

    @Benchmark
    public long adler32Array(Inputs input) {
        input.adler32.reset();
        input.adler32.update(input.data, 7, input.length);
        return input.adler32.getValue();
    }

    @Benchmark
    public long crc32Direct(Inputs input) {
        input.direct.position(7);
        input.crc32.reset();
        input.crc32.update(input.direct);
        return input.crc32.getValue();
    }

    @Benchmark
    public long crc32cDirect(Inputs input) {
        input.direct.position(7);
        input.crc32c.reset();
        input.crc32c.update(input.direct);
        return input.crc32c.getValue();
    }

    @Benchmark
    public long adler32Direct(Inputs input) {
        input.direct.position(7);
        input.adler32.reset();
        input.adler32.update(input.direct);
        return input.adler32.getValue();
    }

    @Benchmark
    public long scalarCrc32(Inputs input) {
        int crc = -1;
        for (int i = 7; i < input.length + 7; i++) {
            crc ^= input.data[i] & 255;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc >>> 1) ^ ((crc & 1) == 0 ? 0 : 0xedb88320);
            }
        }
        return Integer.toUnsignedLong(~crc);
    }

    @Benchmark
    public int encode(TextInputs input) {
        return input.encoder.encode(input.data, input.encodeOutput);
    }

    @Benchmark
    public int decode(TextInputs input) {
        return input.decoder.decode(input.encoded, input.decodeOutput);
    }

    @Benchmark
    public byte[] decodeAllocated(TextInputs input) {
        return input.decoder.decode(input.encoded);
    }

    @Benchmark
    public int decodeMime(MimeInputs input) {
        return input.decoder.decode(input.encoded, input.output);
    }

    @Benchmark
    public byte[] gzipBase64RoundTrip(CompressionInputs input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(input.data, 7, input.length);
        }
        byte[] text = Base64.getEncoder().encode(bytes.toByteArray());
        byte[] compressed = Base64.getDecoder().decode(text);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return gzip.readAllBytes();
        }
    }
}
