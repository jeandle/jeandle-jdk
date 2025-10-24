# Getting Started
## Getting the Source Code and Building
Jeandle is composed of two separate code repositories: [jeandle-llvm](https://github.com/jeandle/jeandle-llvm) and [jeandle-jdk](https://github.com/jeandle/jeandle-jdk). Both repositories need to be built individually, following the same process as their upstream repositories. Note that when building jeandle-jdk, you must specify the installation directory of jeandle-llvm using the option ```--with-jeandle-llvm=<directory>```.

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
Jeandle currently supports X86 and AArch64 architectures. Support for RISC-V architecture is planned for the future. Moreover, by leveraging the powerful ecosystem and well-developed backends of LLVM, other backends may also be supported on demand.
| OS | Arch | Status |
| :---: | :---: | :---: |
| Linux | X86 | Supported |
| Linux | AArch64 | Supported |
| Linux | RISC-V | Planned |

## Cross-compiling

The following steps show how to cross-compile jeandle-jdk for AArch64 architecture. Other platforms also has similar steps. Please refer to the cross-compilation documentation of [LLVM](https://llvm.org/docs/HowToCrossCompileLLVM.html) and [OpenJDK](../doc/building.md#cross-compiling) for detailed guidance.

1. Prepare the cross-compilation toolchain:
```
sudo apt install g++-aarch64-linux-gnu gcc-aarch64-linux-gnu debootstrap qemu-user-static
sudo ln -s /usr/aarch64-linux-gnu/lib/ld-linux-aarch64.so.1  /lib/ld-linux-aarch64.so.1
```

2. Create chroot:
```
sudo debootstrap \
--arch=arm64 --verbose \
--include=fakeroot,symlinks,build-essential,libx11-dev,libxext-dev,libxrender-dev,libxrandr-dev,libxtst-dev,libxt-dev,libcups2-dev,libfontconfig1-dev,libasound2-dev,libfreetype6-dev,libpng-dev,libffi-dev \
--resolve-deps buster \
/home/debian-sysroot/arm64 \
http://httpredir.debian.org/debian/
```

If you use ubuntu, you can use the following command:
```
sudo debootstrap \
--arch=arm64 \
--verbose \
--components=main,universe \
--include=fakeroot,symlinks,build-essential,libx11-dev,libxext-dev,libxrender-dev,libxrandr-dev,libxtst-dev,libxt-dev,libcups2-dev,libfontconfig1-dev,libasound2-dev,libfreetype6-dev,libpng-dev,libffi-dev \
--resolve-deps jammy \
/home/debian-sysroot/arm64 \
http://ports.ubuntu.com/ubuntu-ports/
```

3. Configure and build jeandle-llvm of the host environment:
```
git clone https://github.com/jeandle/jeandle-llvm.git jeandle-llvm-x86
cd jeandle-llvm-x86
mkdir build
cd build
cmake -G "Unix Makefiles" -DLLVM_TARGETS_TO_BUILD=X86 -DCMAKE_BUILD_TYPE="Release" -DCMAKE_INSTALL_PREFIX="/home/jeandle-llvm-x86-install" -DLLVM_BUILD_LLVM_DYLIB=On -DLLVM_DYLIB_COMPONENTS=all ../llvm
cmake --build . --target install --parallel
```

4. (Cross-compiling) Configure and build jeandle-llvm of the target environment:
```
git clone https://github.com/jeandle/jeandle-llvm.git jeandle-llvm-aarch64
sudo chroot /home/debian-sysroot/arm64 symlinks -cr .
cd jeandle-llvm-aarch64
mkdir build
cd build
cmake -G "Unix Makefiles" -DLLVM_TARGETS_TO_BUILD=AArch64 -DCMAKE_BUILD_TYPE="Release" -DCMAKE_INSTALL_PREFIX="/home/jeandle-llvm-aarch64-install" -DLLVM_BUILD_LLVM_DYLIB=On -DLLVM_DYLIB_COMPONENTS=all ../llvm
cmake --build . --target install --parallel
```

5. (Cross-compiling) Configure and build jeandle-jdk of the target environment:

When cross-compiling jeandle-jdk, you must specify the installation directory of jeandle-llvm of the host environment using the option ```--with-host-jeandle-llvm=<directory>``` and specify the installation directory of jeandle-llvm of the target environment using the option ```--with-jeandle-llvm=<directory>```.

```
git clone https://github.com/jeandle/jeandle-jdk.git
sudo chroot /home/debian-sysroot/arm64 symlinks -cr .
cd jeandle-jdk
bash configure \
      --with-boot-jdk=/usr/local/java-21-openjdk-amd64/ \
      --with-debug-level=release \
      --with-sysroot=/home/debian-sysroot/arm64 \
      --openjdk-target=aarch64-linux-gnu \
      --with-host-jeandle-llvm=/home/jeandle-llvm-x86-install \
      --with-jeandle-llvm=/home/jeandle-llvm-aarch64-install
export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:/home/debian-sysroot/arm64/lib:/home/debian-sysroot/arm64/usr/lib:/home/debian-sysroot/arm64/lib/aarch64-linux-gnu:/home/debian-sysroot/arm64/usr/lib/aarch64-linux-gnu:/home/jeandle-llvm-aarch64-install
make images
```

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
