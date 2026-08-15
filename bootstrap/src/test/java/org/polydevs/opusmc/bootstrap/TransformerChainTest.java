package org.polydevs.opusmc.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class TransformerChainTest {
    @Test
    void appliesTransformersInPriorityOrder() throws Exception {
        ClassTransformer appendB = transformer("b", 20, (byte) 'b');
        ClassTransformer appendA = transformer("a", 10, (byte) 'a');
        TransformerChain chain = new TransformerChain(Arrays.asList(appendB, appendA));

        assertArrayEquals(new byte[] {'x', 'a', 'b'}, chain.transform("example.Class", new byte[] {'x'}));
    }

    @Test
    void rejectsDuplicateIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TransformerChain(Arrays.asList(
                        transformer("same", 1, (byte) 'a'), transformer("same", 2, (byte) 'b'))));
    }

    private static ClassTransformer transformer(String id, int priority, byte suffix) {
        return new ClassTransformer() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public byte[] transform(String className, byte[] originalBytecode) {
                byte[] output = Arrays.copyOf(originalBytecode, originalBytecode.length + 1);
                output[output.length - 1] = suffix;
                return output;
            }
        };
    }
}

