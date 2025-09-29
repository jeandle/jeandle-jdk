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
#include<signal.h>
#include<stdlib.h>
#ifdef __linux__
#include <pthread.h>
#endif

/*
 * Class:     NativeThreadHolder
 * Method:    getID
 * Signature: ()J
 */
JNIEXPORT jlong JNICALL Java_compiler_jeandle_vmCrash_NativeThreadHolder_getID(JNIEnv *env, jclass class)
{
#ifdef __linux__
    return (jlong)pthread_self();
#else
    return 0;
#endif
}

/*
 * Class:     NativeThreadHolder
 * Method:    signal
 * Signature: (JI)I
 */
JNIEXPORT jint JNICALL Java_compiler_jeandle_vmCrash_NativeThreadHolder_signal(JNIEnv *env, jclass class, jlong thread, jint sig)
{
#ifdef __linux__
    return pthread_kill((pthread_t)thread, sig);
#else
    return 0;
#endif
}
