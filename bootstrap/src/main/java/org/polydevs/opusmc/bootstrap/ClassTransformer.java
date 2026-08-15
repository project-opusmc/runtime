package org.polydevs.opusmc.bootstrap;

public interface ClassTransformer {
    String id();

    int priority();

    byte[] transform(String className, byte[] originalBytecode) throws Exception;
}

