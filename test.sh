jtreg \
  -jdk:./build/linux-x86_64-server-fastdebug/images/jdk/ \
  -vmoption:-XX:+UseJeandleCompiler \
  -verbose:fail,error,time \
  -timeoutFactor:20 \
  test/hotspot/jtreg/compiler/c2/TestShiftRightAndAccumulate.java
