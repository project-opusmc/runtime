package dev.rbw.bootstrap;

import dev.rbw.core.LifecycleStage;
import dev.rbw.core.ClientTelemetry;
import dev.rbw.core.RbwCore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class BootstrapMain {
    private BootstrapMain() {
    }

    public static void main(String[] args) throws Throwable {
        GameLaunchStatus launchStatus = GameLaunchStatus.fromSystemProperty();
        launchStatus.installShutdownHook();
        ClientTelemetry.startFromSystemProperties();
        try {
            BootstrapArguments arguments = BootstrapArguments.parse(args);
            String[] gameArguments = GameArgumentProtocol.read(System.in);
            List<URL> gameClasspath = readClasspath(arguments.gameClasspathFile());
            TransformerChain transformers = TransformerChain.discover();

            System.out.println("[RBW/BOOT] bootstrap loaded");
            System.out.println("[RBW/BOOT] game classpath entries=" + gameClasspath.size());
            System.out.println("[RBW/BOOT] transformers=" + transformers.size());
            ClientTelemetry.bootstrapLoaded(gameClasspath.size(), transformers.size());
            RbwCore.transition(LifecycleStage.CREATED, LifecycleStage.BOOTSTRAP);

            ClassLoader parent = BootstrapMain.class.getClassLoader();
            try (TransformingClassLoader loader = new TransformingClassLoader(
                    gameClasspath.toArray(new URL[gameClasspath.size()]), parent, transformers)) {
                Thread.currentThread().setContextClassLoader(loader);
                RbwCore.transition(LifecycleStage.BOOTSTRAP, LifecycleStage.GAME_STARTING);
                Class<?> gameMain = Class.forName(arguments.gameMainClass(), true, loader);
                Method main = gameMain.getMethod("main", String[].class);
                RbwCore.transition(LifecycleStage.GAME_STARTING, LifecycleStage.RUNNING);
                launchStatus.markRunning();
                try {
                    main.invoke(null, new Object[] {gameArguments});
                } catch (InvocationTargetException invocation) {
                    throw invocation.getCause();
                }
                launchStatus.markExited();
                RbwCore.transition(LifecycleStage.RUNNING, LifecycleStage.SHUTDOWN);
            }
        } catch (Throwable failure) {
            ClientTelemetry.failure(failure);
            launchStatus.markFailed();
            throw failure;
        }
    }

    private static List<URL> readClasspath(Path classpathFile) throws Exception {
        Path normalizedFile = classpathFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedFile)) {
            throw new IllegalArgumentException("Game classpath file does not exist: " + normalizedFile);
        }

        List<URL> urls = new ArrayList<URL>();
        for (String line : Files.readAllLines(normalizedFile, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) {
                throw new IllegalArgumentException("Game classpath contains an empty entry");
            }
            Path entry = java.nio.file.Paths.get(line).toAbsolutePath().normalize();
            if (!Files.isRegularFile(entry)) {
                throw new IllegalArgumentException("Game classpath entry does not exist: " + entry);
            }
            urls.add(entry.toUri().toURL());
        }
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("Game classpath is empty");
        }
        return urls;
    }
}
