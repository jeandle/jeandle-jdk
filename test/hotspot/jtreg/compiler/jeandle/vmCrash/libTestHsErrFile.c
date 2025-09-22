/*
 * Copyright (c) 2025, the Jeandle-JDK Authors. All Rights Reserved.
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

#include<jni.h>
#if defined(__GNUC__) && defined(__aarch64__)
#define KEEP_FRAME_POINTER __attribute__((target("no-omit-leaf-frame-pointer")))
#elif defined(__GNUC__) && (defined(__x86_64__) || defined(__amd64__))
#define KEEP_FRAME_POINTER __attribute__((optimize("O0")))
#endif

/*
 * Class:     TestHsErrFile
 * Method:    crashInNative
 * Signature: ()V
 */
JNIEXPORT KEEP_FRAME_POINTER void JNICALL Java_TestHsErrFile_crashInNative(JNIEnv *env, jclass class)
{
    *(jint *)0 = 0;
}
