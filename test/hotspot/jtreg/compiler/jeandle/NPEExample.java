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

public class NPEExample {
    private static int testInvoke(MyClass myClass) {
        return myClass.getField();
    }

    private static int testAccess(MyClass myClass) {
        return myClass.field;
    }

    private static int testMulti(MyClass a, MyClass b) {
        int x = a.field + 3;
        if (x < 0) {
            return 0;
        }
        int y = b.field + 4;
        return a.field + b.field;
    }

    public static void main(String[] args) throws Exception {
        Class.forName("MyClass");

        MyClass myClass = null;

        testInvoke(myClass);

        testAccess(myClass);

        testMulti(myClass, myClass);
    }
}

class MyClass {
    public int field = 1;

    public int getField() {
        return field;
    }
}
