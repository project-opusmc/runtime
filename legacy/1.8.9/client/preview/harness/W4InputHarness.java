package org.polydevs.opusmc.client;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.Logger;
import org.polydevs.opusmc.client.embed.OpusWebViewClient;
import org.polydevs.opusmc.websurface.OpusDisplayMetrics;
import org.polydevs.opusmc.websurface.OpusInputMapper;

public final class W4InputHarness {
    private static final int LOGICAL_WIDTH = 800;
    private static final int LOGICAL_HEIGHT = 500;
    private static final float SCALE = 2.0F;
    private static final long TIMEOUT_MS = 30000L;

    private static final int READY = 0x112233;
    private static final int HOVER = 0xA01010;
    private static final int LEAVE = 0x10A010;
    private static final int LEFT = 0x1010A0;
    private static final int RIGHT = 0xA06010;
    private static final int MIDDLE = 0x6010A0;
    private static final int SHIFT_LEFT = 0x109090;
    private static final int WHEEL_UP = 0xD02080;
    private static final int WHEEL_DOWN = 0x20D080;
    private static final int FOCUS = 0x2070D0;
    private static final int TEXT = 0x20A0D0;
    private static final int BACKSPACE = 0xD0A020;
    private static final int ARROW = 0x8040D0;
    private static final int ESCAPE = 0xD04040;
    private static final int TAB = 0x40D040;
    private static final int EDIT_CUT = 0x40D0D0;
    private static final int EDIT_PASTE = 0xD0D040;
    private static final int EDIT_COMMAND_SELECT = 0xB984E8;
    private static final int EDIT_UNDO = 0xE89184;
    private static final int EDIT_REDO = 0x84E89C;

    public static void main(String[] args) throws Exception {
        AtomicBoolean staleInputObserved = new AtomicBoolean();
        Logger log = logger(staleInputObserved);
        String helper = OpusWebViewClient.resolveHelperPath(log);
        require(helper != null, "CEF helper unavailable");

        OpusWebViewClient client = new OpusWebViewClient(log, helper);
        try {
            require(client.start(LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE, dataUrl()),
                    "CEF helper did not start");
            OpusWebViewClient.Frame initial = awaitFrame(client, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            OpusDisplayMetrics metrics = new OpusDisplayMetrics(
                    1L, LOGICAL_WIDTH, LOGICAL_HEIGHT, initial.width, initial.height);
            require(statusRgb(initial, LOGICAL_WIDTH, LOGICAL_HEIGHT) == READY,
                    "initial DOM state was not ready");
            client.release(initial);
            OpusInputMapper mapper = new OpusInputMapper(metrics);

            OpusInputMapper.Pointer framebufferTarget = rawToCss(metrics, mapper, 180, 120);
            OpusInputMapper.Pointer logicalTarget = logicalRawToCss(mapper, 180, 120);
            OpusInputMapper.Pointer target = framebufferTarget;
            OpusInputMapper.Pointer input = rawToCss(metrics, mapper, 180, 242);
            require(logicalTarget.x == target.x && logicalTarget.y == target.y,
                    "logical and framebuffer coordinate oracles disagree");

            client.sendMouseMove(target.x, target.y);
            awaitState(client, HOVER, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            System.out.println("PASS framebuffer-to-CSS pointer mapping drives CEF hover at DPR 2");

            client.sendMouseLeave(target.x, target.y);
            awaitState(client, LEAVE, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            System.out.println("PASS native CEF mouse-leave semantics");

            click(client, target, 0, 0);
            awaitState(client, LEFT, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            click(client, target, 1, 0);
            awaitState(client, RIGHT, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            click(client, target, 2, 0);
            awaitState(client, MIDDLE, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            System.out.println("PASS left/right/middle button semantics");

            client.setSurfaceVisible(false);
            click(client, target, 0, 0);
            require(client.consumeFrame() == null, "parked CEF published click feedback");
            client.setSurfaceVisible(true);
            awaitState(client, MIDDLE, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            click(client, target, 0, 0);
            awaitState(client, LEFT, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            System.out.println("PASS parked click is ignored and visible click reaches DOM");

            click(client, target, 0, OpusInputMapper.MOD_SHIFT);
            awaitState(client, SHIFT_LEFT, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            System.out.println("PASS pointer modifier propagation (Shift+left)");

            client.sendMouseMove(target.x, target.y);
            client.sendWheel(target.x, target.y, 120);
            awaitState(client, WHEEL_UP, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            client.sendWheel(target.x, target.y, -120);
            awaitState(client, WHEEL_DOWN, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            System.out.println("PASS wheel sign/magnitude reaches DOM semantics");

            click(client, input, 0, 0);
            awaitState(client, FOCUS, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            System.out.println("PASS native pointer focus reaches editable input");
            client.sendChar('A');
            client.sendChar(0x1EAF);
            awaitState(client, TEXT, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            System.out.println("PASS Unicode BMP text input (A + ắ)");

            key(client, 0x41, OpusInputMapper.MOD_CONTROL);
            key(client, 0x43, OpusInputMapper.MOD_CONTROL);
            key(client, 0x58, OpusInputMapper.MOD_CONTROL);
            awaitState(client, EDIT_CUT, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            key(client, 0x56, OpusInputMapper.MOD_CONTROL);
            awaitState(client, EDIT_PASTE, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            System.out.println("PASS Control+A/C/X/V performs real browser editing and clipboard operations");

            key(client, 0x41, OpusInputMapper.MOD_COMMAND);
            client.sendChar('Q');
            awaitState(client, EDIT_COMMAND_SELECT, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            System.out.println("PASS Command+A selects the complete editable value");

            key(client, 0x5A, OpusInputMapper.MOD_CONTROL);
            awaitState(client, EDIT_UNDO, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            key(client, 0x59, OpusInputMapper.MOD_CONTROL);
            awaitState(client, EDIT_REDO, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            System.out.println("PASS Control+Z/Y performs real undo and redo");

            key(client, 0x08, 0);
            awaitState(client, BACKSPACE, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            key(client, 0x25, 0);
            awaitState(client, ARROW, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            key(client, 0x1B, 0);
            awaitState(client, ESCAPE, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            key(client, 0x09, 0);
            awaitState(client, TAB, LOGICAL_WIDTH, LOGICAL_HEIGHT, SCALE);
            System.out.println("PASS Backspace/Arrow/Escape/Tab native key semantics");

            verifyStaleGenerationDrop(client, staleInputObserved);
        } finally {
            client.stop();
        }

        System.out.println("W4 CEF INPUT ORACLE: ALL PASSED");
    }

    private static OpusInputMapper.Pointer logicalRawToCss(
            OpusInputMapper mapper, int logicalX, int logicalY) {
        int rawBottomY = LOGICAL_HEIGHT - 1 - logicalY;
        OpusInputMapper.Pointer mapped = mapper.fromLwjglLogical(logicalX, rawBottomY);
        require(mapped.x == logicalX && mapped.y == logicalY,
                "raw logical coordinate did not map back to requested CSS point");
        return mapped;
    }

    private static OpusInputMapper.Pointer rawToCss(OpusDisplayMetrics metrics,
            OpusInputMapper mapper, int logicalX, int logicalY) {
        int rawX = metrics.logicalToFramebufferX(logicalX);
        int rawBottomY = metrics.logicalToFramebufferBottomY(logicalY);
        OpusInputMapper.Pointer mapped = mapper.fromLwjglFramebuffer(rawX, rawBottomY);
        require(mapped.x == logicalX && mapped.y == logicalY,
                "raw framebuffer coordinate did not map back to requested CSS point");
        return mapped;
    }

    private static void click(OpusWebViewClient client, OpusInputMapper.Pointer pointer,
            int button, int modifiers) {
        client.sendMouseMove(pointer.x, pointer.y, modifiers);
        client.sendMouseButton(pointer.x, pointer.y, button, true, modifiers);
        client.sendMouseButton(pointer.x, pointer.y, button, false, modifiers);
    }

    private static void key(OpusWebViewClient client, int windowsVirtualKey, int modifiers) {
        client.sendKey(windowsVirtualKey, true, modifiers);
        client.sendKey(windowsVirtualKey, false, modifiers);
    }

    private static void verifyStaleGenerationDrop(OpusWebViewClient client,
            AtomicBoolean staleInputObserved) throws Exception {
        client.resize(801, LOGICAL_HEIGHT, SCALE);
        OpusWebViewClient.Frame resized = awaitFrame(client, 801, LOGICAL_HEIGHT, SCALE);
        client.release(resized);

        Method sendLine = OpusWebViewClient.class.getDeclaredMethod("sendLine", String.class);
        sendLine.setAccessible(true);
        sendLine.invoke(client, "INPUT 1 down 180 120 0 0");

        long deadline = System.currentTimeMillis() + 5000L;
        while (!staleInputObserved.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        require(staleInputObserved.get(), "helper did not reject stale-generation input");
        System.out.println("PASS stale-generation input is dropped before CEF injection");
    }

    private static void awaitState(OpusWebViewClient client, int expectedRgb,
            int logicalWidth, int logicalHeight, float scale) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            require(!client.isFailed(), "CEF helper entered failed state");
            OpusWebViewClient.Frame frame = client.consumeFrame();
            if (frame != null) {
                try {
                    if (client.isCurrent(frame, logicalWidth, logicalHeight, scale)
                            && statusRgb(frame, logicalWidth, logicalHeight) == expectedRgb) {
                        return;
                    }
                } finally {
                    client.release(frame);
                }
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("timed out waiting for DOM state #"
                + Integer.toHexString(expectedRgb));
    }

    private static int statusRgb(OpusWebViewClient.Frame frame, int logicalWidth, int logicalHeight) {
        OpusDisplayMetrics metrics = new OpusDisplayMetrics(
                1L, logicalWidth, logicalHeight, frame.width, frame.height);
        int x = metrics.logicalToFramebufferX(10);
        int y = metrics.logicalToFramebufferTopY(10);
        int offset = (y * frame.width + x) * 4;
        ByteBuffer pixels = frame.pixels.duplicate();
        int blue = pixels.get(offset) & 0xFF;
        int green = pixels.get(offset + 1) & 0xFF;
        int red = pixels.get(offset + 2) & 0xFF;
        return (red << 16) | (green << 8) | blue;
    }

    private static OpusWebViewClient.Frame awaitFrame(OpusWebViewClient client,
            int logicalWidth, int logicalHeight, float scale) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            require(!client.isFailed(), "CEF helper entered failed state");
            OpusWebViewClient.Frame frame = client.consumeFrame();
            if (frame != null) {
                if (client.isCurrent(frame, logicalWidth, logicalHeight, scale)) return frame;
                client.release(frame);
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("timed out waiting for CEF frame");
    }

    private static String dataUrl() {
        String html = "<!doctype html><html><head><meta charset='utf-8'><style>"
                + "html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#202830;}"
                + "#status{position:fixed;left:0;top:0;width:40px;height:40px;background:#112233;pointer-events:none}"
                + "#target{position:absolute;left:100px;top:80px;width:160px;height:80px;border:0;background:#334455}"
                + "#text{position:absolute;left:100px;top:220px;width:280px;height:44px;font-size:20px}"
                + "</style></head><body><div id='status'></div><button id='target'></button>"
                + "<input id='text' autocomplete='off'/><script>"
                + "const s=document.getElementById('status'),t=document.getElementById('target'),i=document.getElementById('text');"
                + "const c={ready:'#112233',hover:'#a01010',leave:'#10a010',left:'#1010a0',right:'#a06010',middle:'#6010a0',shift:'#109090',up:'#d02080',down:'#20d080',focus:'#2070d0',text:'#20a0d0',back:'#d0a020',arrow:'#8040d0',esc:'#d04040',tab:'#40d040',cut:'#40d0d0',paste:'#d0d040',cmd:'#b984e8',undo:'#e89184',redo:'#84e89c'};"
                + "const state=n=>{s.style.background=c[n]};"
                + "t.addEventListener('mouseenter',()=>state('hover'));t.addEventListener('mouseleave',()=>state('leave'));"
                + "t.addEventListener('mousedown',e=>{if(e.shiftKey&&e.button===0)state('shift');else if(e.button===0)state('left');else if(e.button===2)state('right');else if(e.button===1)state('middle')});"
                + "t.addEventListener('contextmenu',e=>e.preventDefault());"
                + "t.addEventListener('wheel',e=>{e.preventDefault();state(e.deltaY<0?'up':'down')},{passive:false});"
                + "i.addEventListener('focus',()=>state('focus'));"
                + "let editStage='typing';const original=[0x41,0x1eaf];"
                + "i.addEventListener('input',()=>{const cp=Array.from(i.value).map(ch=>ch.codePointAt(0));console.log('W4_INPUT_VALUE:'+cp.map(v=>v.toString(16)).join(','));if(editStage==='typing'&&cp.length===2&&cp[0]===original[0]&&cp[1]===original[1]){editStage='ready';state('text')}else if(editStage==='ready'&&cp.length===0){editStage='cut';state('cut')}else if(editStage==='cut'&&cp.length===2&&cp[0]===original[0]&&cp[1]===original[1]){editStage='pasted';state('paste')}else if(editStage==='pasted'&&i.value==='Q'){editStage='replaced';state('cmd')}else if(editStage==='replaced'&&cp.length===2&&cp[0]===original[0]&&cp[1]===original[1]){editStage='undone';state('undo')}else if(editStage==='undone'&&i.value==='Q'){editStage='redone';state('redo')}});"
                + "document.addEventListener('keydown',e=>{if(e.key==='Backspace')state('back');else if(e.key==='ArrowLeft')state('arrow');else if(e.key==='Escape')state('esc');else if(e.key==='Tab')state('tab')});"
                + "state('ready');</script></body></html>";
        return "data:text/html;base64," + Base64.getEncoder().encodeToString(
                html.getBytes(StandardCharsets.UTF_8));
    }

    private static Logger logger(final AtomicBoolean staleInputObserved) {
        return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(),
                new Class<?>[] {Logger.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] call) {
                        String name = method.getName();
                        if (name.startsWith("is") && method.getReturnType() == boolean.class) {
                            return Boolean.FALSE;
                        }
                        if (("warn".equals(name) || "error".equals(name) || "info".equals(name))
                                && call != null && call.length > 0) {
                            String rendered = format(String.valueOf(call[0]), call);
                            if (rendered.contains("stale input generation")) {
                                staleInputObserved.set(true);
                            }
                            if (rendered.contains("[helper]") || !"info".equals(name)) {
                                System.out.println(name.toUpperCase(Locale.ROOT) + " " + rendered);
                            }
                            for (Object value : call) {
                                if (value instanceof Throwable) {
                                    StringWriter stack = new StringWriter();
                                    ((Throwable) value).printStackTrace(new PrintWriter(stack));
                                    System.out.print(stack.toString());
                                }
                            }
                        }
                        return null;
                    }
                });
    }

    private static String format(String message, Object[] call) {
        String formatted = message;
        if (call == null) return formatted;
        for (int index = 1; index < call.length; index++) {
            Object value = call[index];
            if (value instanceof Object[]) {
                for (Object nested : (Object[]) value) {
                    formatted = formatted.replaceFirst("\\{\\}",
                            java.util.regex.Matcher.quoteReplacement(String.valueOf(nested)));
                }
            } else if (!(value instanceof Throwable)) {
                formatted = formatted.replaceFirst("\\{\\}",
                        java.util.regex.Matcher.quoteReplacement(String.valueOf(value)));
            }
        }
        return formatted;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
