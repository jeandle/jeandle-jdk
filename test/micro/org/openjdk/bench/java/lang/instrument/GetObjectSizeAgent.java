/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 */

package org.openjdk.bench.java.lang.instrument;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public final class GetObjectSizeAgent {
    private static volatile Instrumentation instrumentation;

    private GetObjectSizeAgent() { }

    public static void agentmain(String args, Instrumentation inst) {
        instrumentation = inst;
    }

    static Instrumentation instrumentation() throws Exception {
        Instrumentation result = instrumentation;
        if (result != null) {
            return result;
        }
        synchronized (GetObjectSizeAgent.class) {
            if (instrumentation == null) {
                loadAgent();
            }
            return instrumentation;
        }
    }

    private static void loadAgent() throws Exception {
        Path agentJar = createAgentJar();
        Class<?> virtualMachineClass = Class.forName("com.sun.tools.attach.VirtualMachine");
        Method attach = virtualMachineClass.getMethod("attach", String.class);
        Object virtualMachine = attach.invoke(
                null, Long.toString(ProcessHandle.current().pid()));
        try {
            virtualMachineClass.getMethod("loadAgent", String.class)
                    .invoke(virtualMachine, agentJar.toString());
        } finally {
            virtualMachineClass.getMethod("detach").invoke(virtualMachine);
            Files.deleteIfExists(agentJar);
        }
        if (instrumentation == null) {
            throw new IllegalStateException("agent did not initialize Instrumentation");
        }
    }

    private static Path createAgentJar() throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(new Attributes.Name("Agent-Class"), GetObjectSizeAgent.class.getName());

        Path jar = Files.createTempFile("get-object-size-agent", ".jar");
        String resource = GetObjectSizeAgent.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest);
             InputStream input = GetObjectSizeAgent.class.getClassLoader()
                     .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing agent class resource: " + resource);
            }
            output.putNextEntry(new JarEntry(resource));
            input.transferTo(output);
            output.closeEntry();
        }
        return jar;
    }
}
