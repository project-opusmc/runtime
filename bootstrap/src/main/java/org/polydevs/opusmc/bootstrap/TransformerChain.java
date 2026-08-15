package org.polydevs.opusmc.bootstrap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

public final class TransformerChain {
    private final List<ClassTransformer> transformers;

    public TransformerChain(List<ClassTransformer> transformers) {
        List<ClassTransformer> copy = new ArrayList<ClassTransformer>(transformers);
        Collections.sort(copy, new Comparator<ClassTransformer>() {
            @Override
            public int compare(ClassTransformer left, ClassTransformer right) {
                int priority = Integer.compare(left.priority(), right.priority());
                return priority != 0 ? priority : left.id().compareTo(right.id());
            }
        });

        Set<String> ids = new HashSet<String>();
        for (ClassTransformer transformer : copy) {
            if (transformer == null) {
                throw new IllegalArgumentException("Transformer chain cannot contain null");
            }
            if (transformer.id() == null || transformer.id().trim().isEmpty()) {
                throw new IllegalArgumentException("Transformer id cannot be empty");
            }
            if (!ids.add(transformer.id())) {
                throw new IllegalArgumentException("Duplicate transformer id: " + transformer.id());
            }
        }
        this.transformers = Collections.unmodifiableList(copy);
    }

    public static TransformerChain discover() {
        List<ClassTransformer> discovered = new ArrayList<ClassTransformer>();
        for (ClassTransformer transformer : ServiceLoader.load(ClassTransformer.class)) {
            discovered.add(transformer);
        }
        return new TransformerChain(discovered);
    }

    public int size() {
        return transformers.size();
    }

    public byte[] transform(String className, byte[] originalBytecode) throws Exception {
        byte[] current = originalBytecode;
        for (ClassTransformer transformer : transformers) {
            byte[] transformed = transformer.transform(className, current);
            if (transformed == null) {
                throw new IllegalStateException(
                        "Transformer " + transformer.id() + " returned null for " + className);
            }
            current = transformed;
        }
        return current;
    }
}

