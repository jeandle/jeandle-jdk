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

package compiler.jeandle.vmCrash;

public class NativeThreadHolder {

    public enum Signal {
        /* Signal help (kill -l)
        *  1) SIGHUP       2) SIGINT       3) SIGQUIT      4) SIGILL       5) SIGTRAP
        *  6) SIGABRT      7) SIGBUS       8) SIGFPE       9) SIGKILL     10) SIGUSR1
        * 11) SIGSEGV     12) SIGUSR2     13) SIGPIPE     14) SIGALRM     15) SIGTERM
        * 16) SIGSTKFLT   17) SIGCHLD     18) SIGCONT     19) SIGSTOP     20) SIGTSTP
        * 21) SIGTTIN     22) SIGTTOU     23) SIGURG      24) SIGXCPU     25) SIGXFSZ
        * 26) SIGVTALRM   27) SIGPROF     28) SIGWINCH    29) SIGIO       30) SIGPWR
        * 31) SIGSYS      34) SIGRTMIN    35) SIGRTMIN+1  36) SIGRTMIN+2  37) SIGRTMIN+3
        * 38) SIGRTMIN+4  39) SIGRTMIN+5  40) SIGRTMIN+6  41) SIGRTMIN+7  42) SIGRTMIN+8
        * 43) SIGRTMIN+9  44) SIGRTMIN+10 45) SIGRTMIN+11 46) SIGRTMIN+12 47) SIGRTMIN+13
        * 48) SIGRTMIN+14 49) SIGRTMIN+15 50) SIGRTMAX-14 51) SIGRTMAX-13 52) SIGRTMAX-12
        * 53) SIGRTMAX-11 54) SIGRTMAX-10 55) SIGRTMAX-9  56) SIGRTMAX-8  57) SIGRTMAX-7
        * 58) SIGRTMAX-6  59) SIGRTMAX-5  60) SIGRTMAX-4  61) SIGRTMAX-3  62) SIGRTMAX-2
        * 63) SIGRTMAX-1  64) SIGRTMAX
        */
        SIGHUP(1),
        SIGINT(2),
        SIGQUIT(3),
        SIGILL(4),
        SIGTRAP(5),
        SIGABRT(6),
        SIGBUS(7),
        SIGFPE(8),
        SIGKILL(9),
        SIGSEGV(11),
        SIGPIPE(13),
        SIGTERM(15),
        SIGRTMAX(64);

        private final int number;

        Signal(int number) {
            this.number = number;
        }

        public int getNumber() {
            return number;
        }
    }

    private long pThreadId;

    private Thread thread;

    public static native long getID();

    public static native int signal(long threadId, int sig);

    public long pThreadId() {
        return pThreadId;
    }

    public void setpThreadId(long pThreadId) {
        this.pThreadId = pThreadId;
    }

    public Thread thread() {
        return thread;
    }

    public void setThread(Thread thread) {
        this.thread = thread;
    }

    public void start() {
        thread.start();
    }
}
