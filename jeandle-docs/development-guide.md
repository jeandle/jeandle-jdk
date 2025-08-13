# Development Guide
## Building & Using
Build and use Jeandle as described in [Getting Started](https://github.com/jeandle/jeandle-jdk/blob/main/jeandle-docs/getting-started.md).

## Testing
### Testing JDK
Similar to OpenJDK, jeandle-jdk uses the jtreg test harness. For more information about jtreg, refer to [jtreg](https://openjdk.org/guide/index.html#jtreg).

All tests specific to jeandle-jdk are located in the directory `jeandle-jdk/test/hotspot/jtreg/compiler/jeandle`.

Typically, test cases are designed to verify specific functionalities of Jeandle by compiling and executing certain methods with Jeandle. Therefore, it is necessary to ensure that the test cases are executed with Jeandle enabled. These JVM flags are usually used: ```-XX:+UseJeandleCompiler -Xcomp -XX:-TieredCompilation -XX:CompileCommand,compileonly=${MethodToCompile}```.

### Testing LLVM
Testing for jeandle-llvm follows upstream LLVM practices. For details, refer to the [LLVM Testing Guide](https://llvm.org/docs/TestingGuide.html).

All tests specific to jeandle-llvm are located in the directory `jeandle-llvm/llvm/test/Jeandle`.

## Coding Style
To create high-quality code, please read [hotspot-coding-style](https://github.com/openjdk/jdk/blob/master/doc/hotspot-style.md) and [llvm-coding-standards](https://llvm.org/docs/CodingStandards.html).

If you are working on jeandle-llvm, make sure to use ```clang-format``` to format your code changes. For example, the following command will format the code changed in the most recent commit:
```
git clang-format HEAD~1
```
Note that ```clang-format``` modifies the files, but does not commit them. So you will likely want to add the changes to a commit.

## Header Files in Hotspot Code
The standard library macro ```assert``` used by LLVM conflicts with the macro ```assert``` used by Hotspot. In our Hotspot codebase, we consistently use the Hotspot's ```assert```. To resolve this conflict, it is necessary to include the header file ```"utilities/debug.hpp"``` after including any LLVM or stantard library headers and before using the Hotspot's ```assert```. This header file redefines the ```assert``` for Hotspot code.

## Continuous Integration
The Jeandle project consists of two code repositories: jeandle-jdk and jeandle-llvm. During development, a single feature may require changes to both repositories.

To ensure the Jeandle-JDK GitHub Continuous Integration (CI) pipeline can pull the appropriate branch of jeandle-llvm for compilation and testing when such co-dependent changes exist, include the dependency information in the commit message.

Specifically, in the final commit message for a series of changes in the jeandle-jdk repository that depend on a specific jeandle-llvm branch, add the URL of the jeandle-llvm repository and the name of the required branch. Therefore, the commit message in the jeandle-jdk repository will typically follow this format:
```
Author: <User Name> <user@example.com>
Date:   <Date Info>

    [Detailed description of modifications]

    CO_REPO: https://github.com/<UserID>/jeandle-llvm.git
    CO_BRANCH: <Branch Name>

    Signed-off-by: <User Name> <user@example.com>
```

Note: The jeandle-llvm CI pipeline only compiles and tests jeandle-llvm itself. Therefore, commits within the jeandle-llvm repository are not required to specify any dependency on a jeandle-jdk branch.
