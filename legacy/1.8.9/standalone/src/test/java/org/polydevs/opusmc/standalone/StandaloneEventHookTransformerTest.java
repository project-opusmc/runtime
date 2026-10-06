package org.polydevs.opusmc.standalone;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;

public class StandaloneEventHookTransformerTest {

    @Test
    public void testTransformsMinecraftDisplayGuiScreen() throws Exception {
        File mcJar = new File(System.getProperty("user.home") + "/Library/Application Support/minecraft/versions/1.8.9/1.8.9.jar");
        if (!mcJar.exists()) {
            return; // Skip on environments without standard MC jar
        }

        byte[] aveBytes = null;
        ZipFile zf = new ZipFile(mcJar);
        try {
            ZipEntry entry = zf.getEntry("ave.class");
            assertNotNull(entry, "ave.class must exist in 1.8.9.jar");
            InputStream is = zf.getInputStream(entry);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) {
                baos.write(buf, 0, r);
            }
            aveBytes = baos.toByteArray();
        } finally {
            zf.close();
        }

        StandaloneEventHookTransformer transformer = new StandaloneEventHookTransformer();
        byte[] transformed = transformer.transform("ave", aveBytes);
        assertNotNull(transformed);

        ClassReader cr = new ClassReader(transformed);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        boolean foundGuiHook = false;
        boolean foundTickStart = false;
        boolean foundTickEnd = false;
        boolean foundGameStarted = false;

        for (Object o : cn.methods) {
            MethodNode mn = (MethodNode) o;
            if ("a".equals(mn.name) && "(Laxu;)V".equals(mn.desc)) {
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.INVOKESTATIC) {
                        MethodInsnNode minsn = (MethodInsnNode) insn;
                        if ("onGuiOpen".equals(minsn.name)) {
                            foundGuiHook = true;
                        }
                    }
                }
            } else if ("s".equals(mn.name) && "()V".equals(mn.desc)) {
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.INVOKESTATIC) {
                        MethodInsnNode minsn = (MethodInsnNode) insn;
                        if ("onClientTickStart".equals(minsn.name)) foundTickStart = true;
                        if ("onClientTickEnd".equals(minsn.name)) foundTickEnd = true;
                    }
                }
            } else if ("am".equals(mn.name) && "()V".equals(mn.desc)) {
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.INVOKESTATIC) {
                        MethodInsnNode minsn = (MethodInsnNode) insn;
                        if ("onGameStarted".equals(minsn.name)) foundGameStarted = true;
                    }
                }
            }
        }

        assertTrue(foundGuiHook, "displayGuiScreen must have onGuiOpen hook");
        assertTrue(foundTickStart, "runTick must have onClientTickStart hook");
        assertTrue(foundTickEnd, "runTick must have onClientTickEnd hook");
        assertTrue(foundGameStarted, "startGame must have onGameStarted hook");
    }
}
