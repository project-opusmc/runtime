package dev.rbw.bootstrap;

public interface ClassTransformer {
    String id();

    int priority();

    byte[] transform(String className, byte[] originalBytecode) throws Exception;
}

