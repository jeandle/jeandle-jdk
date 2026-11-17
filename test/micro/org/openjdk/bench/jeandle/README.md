# Jeandle microbenchmarks

`JeandleBitShuffle`, `JeandleMathMisc`, and `JeandlePow` require JMH's Java
blackhole mode. Jeandle does not implement compiler blackholes, so JMH's
automatic selection of that mode can allow the measured computation to be
removed. The benchmark setup rejects compiler blackholes before measurement.
Use the same Java blackhole mode for the Jeandle and C2 runs.

Set the mode on the **JMH harness JVM**, before `-jar`:

```bash
"$JDK/bin/java" -Djmh.blackhole.mode=FULL_DONTINLINE \
    -jar "$BENCHMARKS_JAR" 'org.openjdk.bench.jeandle.Jeandle(BitShuffle|MathMisc|Pow).*' \
    -jvmArgsAppend '-XX:+UseJeandleCompiler -XX:-TieredCompilation'
"$JDK/bin/java" -Djmh.blackhole.mode=FULL_DONTINLINE \
    -jar "$BENCHMARKS_JAR" 'org.openjdk.bench.jeandle.Jeandle(BitShuffle|MathMisc|Pow).*' \
    -jvmArgsAppend '-XX:-UseJeandleCompiler -XX:-TieredCompilation'
```

Through the JDK build, use `JAVA_OPTIONS` so the option reaches the harness:

```bash
make test CONF=release TEST='micro:org.openjdk.bench.jeandle.JeandleBitShuffle' \
    MICRO='JAVA_OPTIONS=-Djmh.blackhole.mode=FULL_DONTINLINE;VM_OPTIONS=-XX:+UseJeandleCompiler -XX:-TieredCompilation'
```

`VM_OPTIONS` or `-jvmArgsAppend` alone only configure the benchmark forks;
they do not change the harness's blackhole selection. Check the JMH header
for `Blackhole mode: full + dont-inline`, and check the compiled hot path
to confirm that the measured operations remain present. Scores include Java
blackhole consumption and should not be compared with compiler-blackhole scores.
