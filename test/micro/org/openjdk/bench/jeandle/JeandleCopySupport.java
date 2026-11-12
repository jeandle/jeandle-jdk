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
package org.openjdk.bench.jeandle;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Correctness checks performed only during benchmark setup. */
final class JeandleCopySupport {
    private JeandleCopySupport() {
    }

    static void checkArray(Object source, Object copy, int from, int length,
                           Class<?> expectedClass) {
        if (copy == source || copy.getClass() != expectedClass ||
                Array.getLength(copy) != length) {
            throw new IllegalStateException("Incorrect copied array identity, type or length");
        }
        boolean primitive = source.getClass().getComponentType().isPrimitive();
        int copied = Math.min(Array.getLength(source) - from, length);
        for (int i = 0; i < length; i++) {
            Object expected = i < copied ? Array.get(source, from + i) : null;
            Object actual = Array.get(copy, i);
            if (primitive ? !actual.equals(expected) : actual != expected) {
                throw new IllegalStateException("Incorrect copied array element " + i);
            }
        }
    }

    static void initializeInstance(Object source) throws ReflectiveOperationException {
        int index = 0;
        for (Class<?> type = source.getClass(); type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                int value = ++index;
                Class<?> fieldType = field.getType();
                if (fieldType == boolean.class) {
                    field.setBoolean(source, (value & 1) != 0);
                } else if (fieldType == byte.class) {
                    field.setByte(source, (byte) (value + 3));
                } else if (fieldType == char.class) {
                    field.setChar(source, (char) ('a' + value));
                } else if (fieldType == short.class) {
                    field.setShort(source, (short) (value * 7));
                } else if (fieldType == int.class) {
                    field.setInt(source, value * 31);
                } else if (fieldType == long.class) {
                    field.setLong(source, 0x1234567800000000L + value);
                } else if (fieldType == float.class) {
                    field.setFloat(source, value + 0.25f);
                } else if (fieldType == double.class) {
                    field.setDouble(source, value + 0.125);
                } else {
                    field.set(source, new Object());
                }
            }
        }
    }

    static void checkInstance(Object source, Object copy, int expectedFields)
            throws ReflectiveOperationException {
        if (copy == source || copy.getClass() != source.getClass()) {
            throw new IllegalStateException("Incorrect cloned instance identity or type");
        }
        int fields = 0;
        for (Class<?> type = source.getClass(); type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                fields++;
                Object expected = field.get(source);
                Object actual = field.get(copy);
                if (field.getType().isPrimitive() ? !actual.equals(expected) : actual != expected) {
                    throw new IllegalStateException("Incorrect cloned field " + field.getName());
                }
            }
        }
        if (fields != expectedFields) {
            throw new IllegalStateException("Unexpected instance field count: " + fields);
        }
    }
}

