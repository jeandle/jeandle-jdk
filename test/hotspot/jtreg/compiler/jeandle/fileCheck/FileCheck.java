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

package compiler.jeandle.fileCheck;

import jdk.test.lib.Asserts;
import jdk.test.lib.Utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Scanner;
import java.util.stream.Collectors;
import java.io.File;
import java.io.FileNotFoundException;
import java.lang.Class;
import java.lang.reflect.Method;

// Used to check required content in files. Treat multiple consecutive spaces as a single space.
public class FileCheck {
    private int lineIndex;
    private List<String> lines;

    public FileCheck(String path, Method method, boolean optimized) throws Exception {
        this(path, method, optimized, 0);
    }

    public FileCheck(String path, Method method, boolean optimized, int fileIndex) throws Exception {
        this.lineIndex = 0;

        Class declaringClass = method.getDeclaringClass();
        String filePrefix = declaringClass.getName().replace('.', '_') + "_" + method.getName() + "_" + getMethodSignature(method).replace('/', '_');
        String fileSuffix = ".ll";
        String optimizedFileSuffix = "-optimized.ll";

        Path folder = Paths.get(path);
        if (!Files.exists(folder)) {
            throw new FileNotFoundException("FileCheck path not found: " + path);
        }
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("FileCheck path must point to a directory");
        }

        List<Path> files =  Files.list(folder)
            .filter(Files::isRegularFile)
            .filter(filterPath -> {
                String fileName = filterPath.getFileName().toString();
                if (!fileName.startsWith(filePrefix)) {
                    return false;
                }
                if (optimized) {
                    return fileName.endsWith(optimizedFileSuffix);
                }
                return fileName.endsWith(fileSuffix) && !fileName.endsWith(optimizedFileSuffix);
            })
            .collect(Collectors.toList());

        if (files.isEmpty()) {
            throw new FileNotFoundException("No matched file found");
        }

        if (files.size() <= fileIndex) {
            throw new IllegalArgumentException("fileIndex out of range");
        }

        List<Path> sortedFiles = files.stream()
                                      .sorted(Comparator.comparing(iter -> iter.getFileName().toString()))
                                      .collect(Collectors.toList());

        this.lines = Files.readAllLines(sortedFiles.get(fileIndex))
                          .stream()
                          .map(str -> str.replaceAll("\\s+", " ").trim())
                          .filter(str -> !str.isEmpty())
                          .collect(Collectors.toList());
    }

    private String getMethodSignature(Method method) {
        StringBuilder signature = new StringBuilder();
        signature.append("(");

        for (Class<?> paramType : method.getParameterTypes()) {
            signature.append(Utils.toJVMTypeSignature(paramType));
        }

        signature.append(")");
        signature.append(Utils.toJVMTypeSignature(method.getReturnType()));

        return signature.toString();
    }

    // Check if str appears in the file.
    public void check(String content) {
        boolean found = false;
        content = content.replaceAll("\\s+", " ").trim();
        while (this.lineIndex < lines.size()) {
            if (lines.get(this.lineIndex).contains(content)) {
                found = true;
                this.lineIndex++;
                break;
            }
            this.lineIndex++;
        }
        Asserts.assertTrue(found, "File check: " + content);
    }

    // Check if str appears in the next line.
    public void checkNext(String content) {
        boolean found = false;
        content = content.replaceAll("\\s+", " ").trim();
        if (this.lineIndex < lines.size()) {
            found = lines.get(this.lineIndex).contains(content);
            this.lineIndex++;
        }
        Asserts.assertTrue(found, "File check next: " + content);
    }

    // Check if str not appears in the file.
    public void checkNot(String content) {
        boolean found = false;
        content = content.replaceAll("\\s+", " ").trim();
        for (String str : this.lines) {
            if (str.contains(content)) {
                found = true;
                break;
            }
        }
        Asserts.assertFalse(found, "File check not: " + content);
    }

    // Check whether the pattern is in the file.
    public void checkPattern(String content) {
        boolean found = false;
        content = content.trim();
        Pattern pattern = Pattern.compile(content);
        while (this.lineIndex < lines.size()) {
            if (pattern.matcher(lines.get(this.lineIndex)).find()) {
                found = true;
                this.lineIndex++;
                break;
            }
            this.lineIndex++;
        }
        Asserts.assertTrue(found, "File check: " + content);
    }

    // Check whether the pattern is in the next line.
    public void checkNextPattern(String content) {
        boolean found = false;
        content = content.trim();
        Pattern pattern = Pattern.compile(content);
        if (this.lineIndex < lines.size()) {
            found = pattern.matcher(lines.get(this.lineIndex)).find();
            this.lineIndex++;
        }
        Asserts.assertTrue(found, "File check next: " + content);
    }

    // Check whether the pattern isn't in the file.
    public void checkNotPattern(String content) {
        boolean found = false;
        content = content.trim();
        Pattern pattern = Pattern.compile(content);
        for (String str : this.lines) {
            if (pattern.matcher(str).find()) {
                found = true;
                break;
            }
        }
        Asserts.assertFalse(found, "File check not: " + content);
    }
}
