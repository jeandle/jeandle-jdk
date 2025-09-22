import jdk.test.lib.Asserts;

/**
 * @test
 * @summary Support newarray
 * issue: https://github.com/jeandle/jeandle-jdk/issues/11
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation -XX:CompileCommand=compileonly,TestNewArray::newArray
 * -XX:+UseJeandleCompiler TestNewArray
 */

public class TestNewArray {
    public static void main(String[] args) {
        newArray();
    }

    public static void newArray() {
        Asserts.assertEQ(new int[10].length, 10);
        Asserts.assertEQ(new double[10].length, 10);
        Asserts.assertEQ(new float[10].length, 10);
        Asserts.assertEQ(new long[10].length, 10);
        Asserts.assertEQ(new short[10].length, 10);
        Asserts.assertEQ(new byte[10].length, 10);
        Asserts.assertEQ(new char[10].length, 10);
        Asserts.assertEQ(new boolean[10].length, 10);
    }
}