# Getting Started
## Getting the Source Code and Building
Jeandle is composed of two separate code repositories: [jeandle-llvm](https://github.com/jeandle/jeandle-jdk) and [jeandle-jdk](https://github.com/jeandle/jeandle-llvm). Both repositories need to be built individually, following the same process as their upstream repositories. Note that when building jeandle-jdk, you must specify the installation directory of jeandle-llvm using the option ```--with-jeandle-llvm=<directory>```.

For detailed guidance on getting started with upstream LLVM and OpenJDK, refer to their official documentation. The links are as follows:
+ LLVM: [https://llvm.org/docs/GettingStarted.html](https://llvm.org/docs/GettingStarted.html)
+ OpenJDK: [https://openjdk.org/guide/](https://openjdk.org/guide/)

To get a quick start, follow the steps below:

1. Clone jeandle-llvm:
```
git clone https://github.com/jeandle/jeandle-llvm.git
```

2. Configure and build jeandle-llvm:
```
cd jeandle-llvm
mkdir build
cd build
cmake -G "Unix Makefiles" -DLLVM_TARGETS_TO_BUILD=X86 -DCMAKE_BUILD_TYPE="Release" -DCMAKE_INSTALL_PREFIX="/home/jeandle-llvm-install" -DLLVM_BUILD_LLVM_DYLIB=On -DLLVM_DYLIB_COMPONENTS=all ../llvm
cmake --build . --target install --parallel
```

3. Clone jeandle-jdk:
```
git clone https://github.com/jeandle/jeandle-jdk.git
```

4. Configure and build jeandle-jdk:
```
cd jeandle-jdk
bash configure \
      --with-boot-jdk=/usr/local/java-21-openjdk-amd64/ \
      --with-debug-level=release \
      --with-jeandle-llvm=/home/jeandle-llvm-install
make images
```
Then the compiled JDK can be found in a directory like ```build/linux-x86_64-server-release/images/jdk/``` under the jeandle-jdk path.

## Debug Builds
The same debug level should be configured for both jeandle-llvm and jeandle-jdk. To build a debug version of Jeandle, use the following build options:
```
// For jeandle-llvm
-DCMAKE_BUILD_TYPE="Debug"
// For jeandle-jdk
--with-debug-level=slowdebug
```

## Supported Platforms
Jeandle currently supports X86 architecture only. Support for Aarch64 architecture is planned for the future. Moreover, by leveraging the powerful ecosystem and well-developed backends of LLVM, other backends may also be supported on demand.
| OS | Arch | Status |
| :---: | :---: | :---: |
| Linux | X86 | Supported |
| Linux | Aarch64 | Planned |


## Using Jeandle
To enable Jeandle, use the JVM flag ```-XX:+UseJeandleCompiler```.

An example of `Fibonacci` is as follows:

```java
public class Main {

    public static int fibonacci(int n) {
        if (n == 0) {
            return 0;
        } else if (n == 1) {
            return 1;
        } else {
            return fibonacci(n - 1) + fibonacci(n - 2);
        }
    }

    public static void main(String[] args) {
        int num = 10;
        for (int i = 0; i < num; i++) {
            System.out.print(fibonacci(i) + " ");
        }
    }
}
```
To skip the interpreter and control which methods are compiled, run Jeandle with the following command:

```
javac Main.java
java -XX:-TieredCompilation -Xcomp \
     -XX:CompileCommand=compileonly,Main::fibonacci \
     -XX:+UseJeandleCompiler Main
```

Output:

```
0 1 1 2 3 5 8 13 21 34
```
