/*
 * Copyright (c) 2002-2008 LWJGL Project
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'LWJGL' nor the names of
 *   its contributors may be used to endorse or promote products derived
 *   from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.lwjgl.opengl;

/*
 * This is the Display implementation interface. Display delegates
 * to implementors of this interface. There is one DisplayImplementation
 * for each supported platform.
 * 
 * @author elias_naur <elias_naur@users.sourceforge.net>
 * @author mojang
 * @author kappaOne <one.kappa@gmail.com>
 */

import java.awt.Canvas;
import java.awt.Robot;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Cursor;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.BufferUtils;
import org.lwjgl.MemoryUtil;
import org.lwjgl.LWJGLException;
import org.lwjgl.LWJGLUtil;

import static org.lwjgl.opengl.GL11.*;

public final class MacOSXDisplay implements DisplayImplementation {
	private static final String OPUS_COMPATIBILITY_MARKER = "OPUS_MODERN_MACOS_DISPLAY_COMPAT_V1";
	private static final String OPUS_RESIZE_COMPATIBILITY_MARKER = "OPUS_COCOA_LIVE_RESIZE_HANDSHAKE_V10_APPKIT_DRAWABLE_UPDATE";
	private static final String OPUS_RESIZE_GUARD_LIBRARY = "opus_lwjgl_resize_guard";
	private static final String OPUS_LIVE_RESIZE_GUARD_PROPERTY = "opus.lwjgl.macos.liveResizeGuard";
	private static final long LIVE_RESIZE_PARK_TIMEOUT_MILLIS = 2_000L;
	private static final int OPUS_LIVE_RESIZE_FRAME_RATE = 240;
	private static final int PBUFFER_HANDLE_SIZE = 24;
	private static final int GAMMA_LENGTH = 256;
	private static boolean resizeGuardInstalled;
	/*
	 * Minecraft 1.8.9 deliberately limits the title and pause screen to 30 FPS
	 * when no world is loaded. That is reasonable at rest, but Cocoa can
	 * deliver many native geometry steps between those frames. During a live
	 * drag the Minecraft bytecode patch calls opusLiveResizeFramerate(), which
	 * temporarily raises the limit so each prepared drawable gets a fresh
	 * frame before AppKit presents the next geometry step.
	 */
	private static volatile boolean opusLiveResize;

	//private MacOSXCanvasListener canvas_listener;
	private Canvas canvas;
	private Robot robot;
	private MacOSXMouseEventQueue mouse_queue;
	private KeyboardEventQueue keyboard_queue;
	private DisplayMode requested_mode;
	
	/* Members for native window use */
	private MacOSXNativeMouse mouse;
	private MacOSXNativeKeyboard keyboard;
	private ByteBuffer window;
	private ByteBuffer context;
	
	private boolean skipViewportValue = false;
	private static final IntBuffer current_viewport = BufferUtils.createIntBuffer(16);
	
	private boolean mouseInsideWindow;
	
	private boolean close_requested;
	
	private boolean native_mode = true;
	
	private boolean updateNativeCursor = false;
	
	private long currentNativeCursor = 0;
	
	private boolean enableHighDPI = false;
	
	private float scaleFactor = 1.0f;

	/*
	 * AppKit owns native edge/corner dragging on its main thread, while
	 * Minecraft owns the NSOpenGLContext on the render thread. Modern macOS
	 * can otherwise replace the Metal-backed drawable while a GL command
	 * encoder is still open. The native guard brackets every Cocoa geometry
	 * update with a render-thread handshake. wasResized() is deliberately the
	 * rendezvous point: LWJGL calls it immediately after swapping a complete
	 * frame and before Minecraft consumes the new display dimensions. The
	 * render thread releases the context at that boundary so AppKit can update
	 * the drawable before it presents the new view geometry.
	 */
	private final Object liveResizeMonitor = new Object();
	private volatile boolean liveResizeRequested;
	private long liveResizeSequence;
	private long liveResizeStepRequested;
	private long liveResizeStepPrepared;
	private long liveResizeStepApplied;
	private long appKitResizeStep;

	/**
	 * The stock LWJGL 2 macOS implementation calls NSOpenGLContext.update()
	 * from every Display.processMessages() pass. That was harmless on the old
	 * OpenGL stack, but current macOS translates the context to Metal and can
	 * still have a render encoder open when update() is called during a live
	 * resize. Keep the last native size so the context is updated only when
	 * Cocoa actually changed the drawable geometry.
	 */
	private int lastWindowWidth = -1;
	private int lastWindowHeight = -1;

	MacOSXDisplay() {
		
	}

	private static native boolean nInstallLiveResizeGuard();

	private static synchronized boolean ensureLiveResizeGuardInstalled() throws LWJGLException {
		if (!Boolean.parseBoolean(System.getProperty(OPUS_LIVE_RESIZE_GUARD_PROPERTY, "true"))) {
			System.err.println("[OPUS/LWJGL] Cocoa live-resize guard disabled for the isolated preview host");
			return false;
		}
		if (resizeGuardInstalled) {
			return true;
		}
		try {
			System.loadLibrary(OPUS_RESIZE_GUARD_LIBRARY);
		} catch (UnsatisfiedLinkError error) {
			throw new LWJGLException("Could not load the OPUS Cocoa live-resize guard", error);
		}
		if (!nInstallLiveResizeGuard()) {
			throw new LWJGLException("Could not install the OPUS Cocoa live-resize guard");
		}
		resizeGuardInstalled = true;
		System.err.println("[OPUS/LWJGL] Cocoa live-resize guard installed");
		return true;
	}

	private native ByteBuffer nCreateWindow(int x, int y, int width, int height, boolean fullscreen, boolean undecorated, boolean resizable, boolean parented, boolean enableFullscreenModeAPI, boolean enableHighDPI, ByteBuffer peer_info_handle, ByteBuffer window_handle) throws LWJGLException;

	private native Object nGetCurrentDisplayMode();
	
	private native void nGetDisplayModes(Object modesList);
	
	private native boolean nIsMiniaturized(ByteBuffer window_handle);
    
	private native boolean nIsFocused(ByteBuffer window_handle);
    
	private native void nSetResizable(ByteBuffer window_handle, boolean resizable);

	/**
	 * Disables AppKit's window-level live-resize snapshot before the first
	 * user drag. The native guard performs the property mutation on the
	 * AppKit main thread; changing it only from viewWillStartLiveResize is
	 * late enough for AppKit to have already selected a stale snapshot.
	 */
	private native void nDisableLiveResizeContentPreservation(ByteBuffer window_handle);
    
	private native void nResizeWindow(ByteBuffer window_handle, int x, int y, int width, int height);

	/**
	 * The live-proof resize is synthetic test traffic, not a real user drag.
	 * AppKit rejects the stock LWJGL frame mutation when it is invoked from
	 * Minecraft's render thread on current macOS, so the proof-only path
	 * marshals the same geometry operation onto AppKit's main queue.
	 */
	private native void nResizeWindowOnMainThread(
			ByteBuffer window_handle,
			int x,
			int y,
			int width,
			int height);
    
	private native boolean nWasResized(ByteBuffer window_handle);

	/**
	 * Returns the frame limit Minecraft should use while Cocoa is actively
	 * resizing the native window.
	 *
	 * <p>This method is intentionally hosted in the compatibility display
	 * class: it keeps the live-resize state beside the AppKit handshake and
	 * avoids a second cross-mod callback into Minecraft's private resize
	 * method.</p>
	 */
	public static int opusLiveResizeFramerate(int normalFramerate) {
		return opusLiveResize
				? Math.max(OPUS_LIVE_RESIZE_FRAME_RATE, normalFramerate)
				: normalFramerate;
	}
	
	private native int nGetX(ByteBuffer window_handle);
	
	private native int nGetY(ByteBuffer window_handle);
	
	private native int nGetWidth(ByteBuffer window_handle);
	
	private native int nGetHeight(ByteBuffer window_handle);
	
	private native boolean nIsNativeMode(ByteBuffer peer_info_handle);
    
	private static boolean isUndecorated() {
		return Display.getPrivilegedBoolean("org.lwjgl.opengl.Window.undecorated");
	}
    
	public void createWindow(final DrawableLWJGL drawable, DisplayMode mode, Canvas parent, int x, int y) throws LWJGLException {
		boolean liveResizeGuardEnabled = ensureLiveResizeGuardInstalled();
		boolean fullscreen = Display.isFullscreen();
		boolean resizable = Display.isResizable();
		boolean parented = (parent != null) && !fullscreen;
		
		// OS X fullscreen mode API is only available on OS X 10.7+
		boolean enableFullscreenModeAPI = LWJGLUtil.isMacOSXEqualsOrBetterThan(10, 7) && parent == null &&
											!Display.getPrivilegedBoolean("org.lwjgl.opengl.Display.disableOSXFullscreenModeAPI");
		
		// OS X high DPI mode is only available on OS X 10.7+
		enableHighDPI = LWJGLUtil.isMacOSXEqualsOrBetterThan(10, 7) && parent == null &&
											(Display.getPrivilegedBoolean("org.lwjgl.opengl.Display.enableHighDPI") || fullscreen);
		
		if (parented) this.canvas = parent;
		else this.canvas = null;
		
		close_requested = false;
		
		DrawableGL gl_drawable = (DrawableGL)Display.getDrawable();
		PeerInfo peer_info = gl_drawable.peer_info;
		ByteBuffer peer_handle = peer_info.lockAndGetHandle();
		ByteBuffer window_handle = parented ? ((MacOSXCanvasPeerInfo)peer_info).window_handle : window;
		
		try {
			// AppKit requires NSWindow style-mask changes on its main thread. The
			// Minecraft render thread cannot safely call nSetResizable later, so
			// make every ordinary native window resizable when it is created.
			boolean nativeResizable = fullscreen ? resizable : true;
			window = nCreateWindow(x, y, mode.getWidth(), mode.getHeight(),
					fullscreen, isUndecorated(), nativeResizable,
					parented, enableFullscreenModeAPI, enableHighDPI, peer_handle, window_handle);
			if (liveResizeGuardEnabled && !fullscreen) {
				nDisableLiveResizeContentPreservation(window);
			}
			lastWindowWidth = nGetWidth(window);
			lastWindowHeight = nGetHeight(window);
            
			if (fullscreen) {
				// when going to fullscreen viewport is set to screen size by Cocoa, ignore this value
				skipViewportValue = true;
				// if starting in fullscreen then set initial viewport to displaymode size
				current_viewport.put(2, mode.getWidth());
				current_viewport.put(3, mode.getHeight());
			}
			
			native_mode = nIsNativeMode(peer_handle);
			
			if (!native_mode) {
				robot = AWTUtil.createRobot(canvas);
			}
			
		} catch (LWJGLException e) {
			destroyWindow();
			throw e;
		} finally {
			peer_info.unlock();
		}
	}

	public void doHandleQuit() {
		synchronized (this) {
			close_requested = true;
		}
	}
	
	public void mouseInsideWindow(boolean inside) {
		synchronized (this) {
			mouseInsideWindow = inside;
		}
		updateNativeCursor = true;
	}
	
	public void setScaleFactor(float scale) {
		synchronized (this) {
			scaleFactor = scale;
		}
	}

	public native void nDestroyCALayer(ByteBuffer peer_info_handle);
	
	public native void nDestroyWindow(ByteBuffer window_handle);

	public void destroyWindow() {
		synchronized (liveResizeMonitor) {
			liveResizeRequested = false;
			opusLiveResize = false;
			liveResizeMonitor.notifyAll();
		}
		
		if (!native_mode) {
			DrawableGL gl_drawable = (DrawableGL)Display.getDrawable();
			PeerInfo peer_info = gl_drawable.peer_info;
			if (peer_info != null) {
				ByteBuffer peer_handle = peer_info.getHandle();
				nDestroyCALayer(peer_handle);
			}
			robot = null;
		}
		
		nDestroyWindow(window);
	}

	public int getGammaRampLength() {
		return GAMMA_LENGTH;
	}

	public native void setGammaRamp(FloatBuffer gammaRamp) throws LWJGLException;

	public String getAdapter() {
		return null;
	}

	public String getVersion() {
		return null;
	}
	
	private static boolean equals(DisplayMode mode1, DisplayMode mode2) {
		return mode1.getWidth() == mode2.getWidth() && mode1.getHeight() == mode2.getHeight()
			&& mode1.getBitsPerPixel() == mode2.getBitsPerPixel() && mode1.getFrequency() == mode2.getFrequency();
	}

	public void switchDisplayMode(DisplayMode mode) throws LWJGLException {
		DisplayMode[] modes = getAvailableDisplayModes();
		
		for (DisplayMode available_mode : modes) {
			if (equals(available_mode, mode)) {
				requested_mode = available_mode;
				return;
			}
		}
		
		throw new LWJGLException(mode + " is not supported");
	}

	public void resetDisplayMode() {
        requested_mode = null;
		restoreGamma();
	}

	private native void restoreGamma();

	public Object createDisplayMode(int width, int height, int bitsPerPixel, int refreshRate) {
		return new DisplayMode(width, height, bitsPerPixel, refreshRate);
	}
	
	public DisplayMode init() throws LWJGLException {
		// Modern macOS can expose a tagged/non-CFString pixel encoding through
		// the legacy CGDisplayMode API. The old native implementation crashes
		// while asking for the initial mode, before Minecraft creates a window.
		// A stable desktop-compatible mode is sufficient for LWJGL's windowed
		// startup; Minecraft supplies the actual requested window dimensions.
		return new DisplayMode(1280, 720, 32, 60);
	}
	
	@SuppressWarnings("unchecked")
	public void addDisplayMode(Object modesList, int width, int height, int bitsPerPixel, int refreshRate) {
		List<DisplayMode> modes = (List<DisplayMode>) modesList;
		DisplayMode displayMode = new DisplayMode(width, height, bitsPerPixel, refreshRate);
		modes.add(displayMode);
	}

	public DisplayMode[] getAvailableDisplayModes() throws LWJGLException {
		// Avoid the legacy CGDisplayMode enumeration path; it can pass a
		// non-CFString pixel encoding into CFStringCompare on current macOS.
		return new DisplayMode[] { new DisplayMode(1280, 720, 32, 60) };
	}

	private native void nSetTitle(ByteBuffer window_handle, ByteBuffer title_buffer);

	public void setTitle(String title) {
		ByteBuffer buffer = MemoryUtil.encodeUTF8(title);
		nSetTitle(window, buffer);
	}

	public boolean isCloseRequested() {
		boolean result;
		synchronized (this) {
			result = close_requested;
			close_requested = false;
		}
		return result;
	}

	public boolean isVisible() {
        return true;
	}

	public boolean isActive() {
		if (native_mode) {
			return nIsFocused(window);
		}
		else {
			return Display.getParent().hasFocus();
		}
	}

	public Canvas getCanvas() {
		return canvas;
	}

	public boolean isDirty() {
		return false;
	}

	public PeerInfo createPeerInfo(PixelFormat pixel_format, ContextAttribs attribs) throws LWJGLException {
		try {
			return new MacOSXDisplayPeerInfo(pixel_format, attribs, true);
		} catch (LWJGLException e) {
			return new MacOSXDisplayPeerInfo(pixel_format, attribs, false);
		}
	}

	public void update() {
		/*
		 * Display.update() runs before Minecraft asks wasResized(). During a
		 * Cocoa live-resize step the native view can already expose the next
		 * size at this point. Letting the ordinary path consume it first
		 * records the new dimensions while restoring the old viewport; the
		 * render-thread handshake then sees no geometry change and the
		 * compositor stretches that intermediate frame. Live geometry is
		 * therefore consumed only by synchronizeLiveResizeStep(), immediately
		 * after the render thread has been released by AppKit.
		 */
		if (!liveResizeRequested) {
			updateNativeDrawable(false);
		}
		
		if (native_mode && updateNativeCursor) {
			updateNativeCursor = false;
			try {
				setNativeCursor(currentNativeCursor);
			} catch (LWJGLException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Refreshes the NSOpenGL drawable on Minecraft's render thread after Cocoa
	 * changes the native view geometry. No AppKit callback may perform these GL
	 * or context operations directly.
	 */
	private boolean updateNativeDrawable(boolean liveResizeFrame) {
		/*
		 * Keep a defensive check here as well as in update(). This method is
		 * the sole place that mutates the drawable/viewport and future callers
		 * must not be able to bypass the live-resize ownership boundary.
		 */
		if (!liveResizeFrame && liveResizeRequested) {
			return false;
		}

		DrawableGL drawable = (DrawableGL)Display.getDrawable();
		int nativeWidth = nGetWidth(window);
		int nativeHeight = nGetHeight(window);
		boolean geometryChanged = nativeWidth > 0 && nativeHeight > 0
				&& (nativeWidth != lastWindowWidth || nativeHeight != lastWindowHeight);

		if (!geometryChanged) {
			return false;
		}

		// Save the current viewport size as Cocoa automatically sets it to the
		// drawable size when NSOpenGLContext.update() runs.
		if (skipViewportValue) {
			skipViewportValue = false;
		} else {
			glGetInteger(GL_VIEWPORT, current_viewport);
		}

		/*
		 * synchronizeLiveResizeStep() already fenced the completed frame before
		 * releasing the AppKit geometry callback. Do not fence a second time on
		 * the live path: that turns every native drag step into two GPU waits
		 * and is visible as hitching when the window is moved quickly. The
		 * ordinary non-live path still needs its own fence before update().
		 *
		 * The AppKit setFrameSize: hook updates the drawable while the render
		 * thread has released the context. The render thread only restores the
		 * viewport after it reacquires the context. The ordinary non-live path
		 * keeps the stock render-thread update behavior.
		 */
		if (!liveResizeFrame) {
			GL11.glFinish();
			drawable.context.update();
		}
		lastWindowWidth = nativeWidth;
		lastWindowHeight = nativeHeight;

		/*
		 * During a guarded live-resize step Minecraft has not yet reached its
		 * normal post-Display.update resize callback. Restoring the old
		 * viewport in that narrow interval makes the newly sized drawable
		 * present through the previous viewport, which macOS then visibly
		 * stretches until the mouse is released. Render the prepared frame
		 * against the new drawable immediately; the ordinary path retains the
		 * stock LWJGL viewport-preservation behavior.
		 */
		if (liveResizeFrame) {
			glViewport(0, 0, nativeWidth, nativeHeight);
		} else {
			glViewport(current_viewport.get(0), current_viewport.get(1), current_viewport.get(2), current_viewport.get(3));
		}
		return true;
	}

	/** Called by the AppKit main thread before NSView live resizing begins. */
	public void doBeginLiveResize() {
		synchronized (liveResizeMonitor) {
			liveResizeSequence++;
			liveResizeRequested = true;
			opusLiveResize = true;
			liveResizeStepRequested = 0L;
			liveResizeStepPrepared = 0L;
			liveResizeStepApplied = 0L;
			appKitResizeStep = 0L;
			liveResizeMonitor.notifyAll();
			System.err.println(
					"[OPUS/LWJGL] live-resize started sequence=" + liveResizeSequence);
		}
	}

	/**
	 * Called by AppKit immediately before one live-resize geometry update.
	 * Returns only after the render thread has swapped and fenced the preceding
	 * frame, so Cocoa cannot replace a drawable that is still being encoded.
	 */
	public boolean doPrepareLiveResizeStep() {
		synchronized (liveResizeMonitor) {
			if (!liveResizeRequested) {
				return false;
			}

			long step = ++liveResizeStepRequested;
			appKitResizeStep = step;
			liveResizeMonitor.notifyAll();
			long deadline = System.currentTimeMillis() + LIVE_RESIZE_PARK_TIMEOUT_MILLIS;
			while (liveResizeRequested && liveResizeStepPrepared < step) {
				long remaining = deadline - System.currentTimeMillis();
				if (remaining <= 0L) {
					liveResizeRequested = false;
					opusLiveResize = false;
					liveResizeMonitor.notifyAll();
					System.err.println(
							"[OPUS/LWJGL] live-resize prepare timed out sequence="
									+ liveResizeSequence + " step=" + step);
					return false;
				}
				try {
					liveResizeMonitor.wait(Math.min(remaining, 50L));
				} catch (InterruptedException ignored) {
					// AppKit must complete or cancel the native size callback even
					// when an unrelated Java interrupt reaches its attached thread.
				}
			}
			return liveResizeRequested && liveResizeStepPrepared >= step;
		}
	}

	/**
	 * Called by AppKit after applying the prepared geometry. The render thread
	 * owns the drawable update, so this callback only acknowledges that the
	 * geometry is now safe to consume and immediately returns to AppKit.
	 *
	 * <p>Waiting here for a complete Minecraft frame serializes native mouse
	 * events with the game frame rate. On a fast drag that makes AppKit expose
	 * a sequence of stale intermediate composites. Content preservation is
	 * disabled on the native view, and the render thread fences and consumes
	 * the newest pending geometry before continuing.</p>
	 */
	public void doCommitLiveResizeStep() {
		synchronized (liveResizeMonitor) {
			long step = appKitResizeStep;
			if (!liveResizeRequested || step <= 0L) {
				return;
			}

			liveResizeStepApplied = Math.max(liveResizeStepApplied, step);
			liveResizeMonitor.notifyAll();
		}
	}

	/** Called by the AppKit main thread after NSView live resizing ends. */
	public void doEndLiveResize() {
		synchronized (liveResizeMonitor) {
			liveResizeRequested = false;
			opusLiveResize = false;
			liveResizeMonitor.notifyAll();
			System.err.println(
					"[OPUS/LWJGL] live-resize ended sequence=" + liveResizeSequence
							+ " requestedSteps=" + liveResizeStepRequested
							+ " appliedSteps=" + liveResizeStepApplied);
		}
	}

	private boolean synchronizeLiveResizeStep() {
		long resizeStep;
		synchronized (liveResizeMonitor) {
			if (!liveResizeRequested) {
				return false;
			}

			if (liveResizeStepRequested <= liveResizeStepPrepared) {
				return false;
			}

			resizeStep = liveResizeStepRequested;
		}

		/*
		 * Display calls this method immediately after swapBuffers(), so this
		 * fence closes the preceding frame before AppKit changes the view.
		 * AppKit must own the drawable update for the live path: the native
		 * callback runs only after this render thread has released the context.
		 */
		GL11.glFinish();

		DrawableGL drawable = (DrawableGL)Display.getDrawable();
		boolean contextReleased = false;
		try {
			if (drawable.context.isCurrent()) {
				drawable.context.releaseCurrent();
				contextReleased = true;
			}
		} catch (LWJGLException error) {
			synchronized (liveResizeMonitor) {
				liveResizeRequested = false;
				opusLiveResize = false;
				liveResizeMonitor.notifyAll();
			}
			System.err.println(
					"[OPUS/LWJGL] could not release the live-resize GL context: "
							+ error);
			return false;
		}

		boolean geometryApplied = false;
		synchronized (liveResizeMonitor) {
			if (liveResizeRequested) {
				resizeStep = liveResizeStepRequested;
				liveResizeStepPrepared = resizeStep;
				liveResizeMonitor.notifyAll();

				/*
				 * doCommitLiveResizeStep() is called by AppKit after
				 * setFrameSize: has run and NSOpenGLContext.update() has
				 * attached the new drawable. Keep the context released until
				 * that point. If live resize ends while this step is in
				 * flight, still consume a step that was already committed.
				 */
				while (liveResizeRequested && liveResizeStepApplied < resizeStep) {
					try {
						liveResizeMonitor.wait();
					} catch (InterruptedException ignored) {
						// Do not resume GL work until AppKit applies the prepared size.
					}
				}
			}
			/*
			 * The AppKit callback can acknowledge the prepared geometry and
			 * then finish the live-resize transaction before this render
			 * thread reacquires the monitor. Check the acknowledgement even
			 * after liveResizeRequested becomes false so that committed final
			 * steps are still consumed instead of being skipped.
			 */
			geometryApplied = liveResizeStepApplied >= resizeStep;
		}

		if (contextReleased) {
			try {
				/*
				 * AppKit has finished its context update. Rebind the context
				 * before any GL query or viewport mutation on this thread.
				 */
				drawable.context.makeCurrent();
			} catch (LWJGLException error) {
				synchronized (liveResizeMonitor) {
					liveResizeRequested = false;
					opusLiveResize = false;
					liveResizeMonitor.notifyAll();
				}
				System.err.println(
						"[OPUS/LWJGL] could not reacquire the live-resize GL context: "
								+ error);
				return false;
			}
		}

		return geometryApplied && updateNativeDrawable(true);
	}

	public void reshape(int x, int y, int width, int height) {
        //if (native_mode) {
        //    nResizeWindow(window, x, y, width, height);
        //}
	}

	/* Mouse */
	public boolean hasWheel() {
		return AWTUtil.hasWheel();
	}

	public int getButtonCount() {
		return AWTUtil.getButtonCount();
	}

	public void createMouse() throws LWJGLException {
		if (native_mode) {
			mouse = new MacOSXNativeMouse(this, window);
			mouse.register();
		}
		else {
			this.mouse_queue = new MacOSXMouseEventQueue(canvas);
			mouse_queue.register();
		}
	}

	public void destroyMouse() {
		if (native_mode) {
			// restore default native cursor
			try {
				MacOSXNativeMouse.setCursor(0);
			} catch (LWJGLException e) {};
			
			// release any mouse grab
			grabMouse(false);
			
			if (mouse != null) {
				mouse.unregister();
			}
			mouse = null;
		}
		else {
			if (mouse_queue != null) {
				MacOSXMouseEventQueue.nGrabMouse(false);
				mouse_queue.unregister();
			}
			this.mouse_queue = null;
		}
	}

	public void pollMouse(IntBuffer coord_buffer, ByteBuffer buttons_buffer) {
		if (native_mode) {
			mouse.poll(coord_buffer, buttons_buffer);
		}
		else {
			mouse_queue.poll(coord_buffer, buttons_buffer);
		}
	}

	public void readMouse(ByteBuffer buffer) {
		if (native_mode) {
			mouse.copyEvents(buffer);
		}
		else {
			mouse_queue.copyEvents(buffer);
		}
	}

	public void grabMouse(boolean grab) {
		if (native_mode) {
			mouse.setGrabbed(grab);
		}
		else {
			mouse_queue.setGrabbed(grab);
		}
	}

	public int getNativeCursorCapabilities() {
		if (native_mode) {
			return Cursor.CURSOR_ONE_BIT_TRANSPARENCY | Cursor.CURSOR_8_BIT_ALPHA | Cursor.CURSOR_ANIMATION;
		}
		
		return AWTUtil.getNativeCursorCapabilities();
	}

	public void setCursorPosition(int x, int y) {
		if (native_mode) {
			if (mouse != null) {
				mouse.setCursorPosition(x, y);
			}
		}
		//else {
			//MacOSXMouseEventQueue.nWarpCursor(x, y);
		//}
	}

	public void setNativeCursor(Object handle) throws LWJGLException {
		if (native_mode) {
			currentNativeCursor = getCursorHandle(handle);
			if (Display.isCreated()) {
				if (mouseInsideWindow) MacOSXNativeMouse.setCursor(currentNativeCursor);
				else MacOSXNativeMouse.setCursor(0); // restore default cursor if outside Display
			}
		}
	}

	public int getMinCursorSize() {
		return 1;
	}

	public int getMaxCursorSize() {
		// as there is no max cursor size limit on OS X
		// return the max cursor size as half the screen resolution
		DisplayMode dm = Display.getDesktopDisplayMode();
		return Math.min(dm.getWidth(), dm.getHeight()) / 2;
	}

	/* Keyboard */
	public void createKeyboard() throws LWJGLException {
		if (native_mode) {
			this.keyboard = new MacOSXNativeKeyboard(window);
			keyboard.register();
		}
		else {
			this.keyboard_queue = new KeyboardEventQueue(canvas);
			keyboard_queue.register();
		}
	}

	public void destroyKeyboard() {
		if (native_mode) {
			if (keyboard != null) {
				keyboard.unregister();
			}
			keyboard = null;
		}
		else {
			if (keyboard_queue != null) {
				keyboard_queue.unregister();
			}
			this.keyboard_queue = null;
		}
	}

	public void pollKeyboard(ByteBuffer keyDownBuffer) {
		if (native_mode) {
			keyboard.poll(keyDownBuffer);
		}
		else {
			keyboard_queue.poll(keyDownBuffer);
		}
	}

	public void readKeyboard(ByteBuffer buffer) {
		if (native_mode) {
			keyboard.copyEvents(buffer);
		}
		else {
			keyboard_queue.copyEvents(buffer);
		}
	}
	
	/** Native cursor handles */
	public Object createCursor(int width, int height, int xHotspot, int yHotspot, int numImages, IntBuffer images, IntBuffer delays) throws LWJGLException {
		if (native_mode) {
			long cursor = MacOSXNativeMouse.createCursor(width, height, xHotspot, yHotspot, numImages, images, delays);
			return cursor;
		}
		else {
			return AWTUtil.createCursor(width, height, xHotspot, yHotspot, numImages, images, delays);
		}
	}

	public void destroyCursor(Object cursor_handle) {
		long handle = getCursorHandle(cursor_handle);
		
		// reset current cursor if same
		if (currentNativeCursor == handle) {
			currentNativeCursor = 0;
		}
		
		MacOSXNativeMouse.destroyCursor(handle);
	}
	
	private static long getCursorHandle(Object cursor_handle) {
		return cursor_handle != null ? (Long)cursor_handle : 0;
	}

	public int getPbufferCapabilities() {
		return Pbuffer.PBUFFER_SUPPORTED;
	}

	public boolean isBufferLost(PeerInfo handle) {
		return false;
	}

	public PeerInfo createPbuffer(int width, int height, PixelFormat pixel_format, ContextAttribs attribs,
			IntBuffer pixelFormatCaps,
			IntBuffer pBufferAttribs) throws LWJGLException {
		return new MacOSXPbufferPeerInfo(width, height, pixel_format, attribs);
	}

	public void setPbufferAttrib(PeerInfo handle, int attrib, int value) {
		throw new UnsupportedOperationException();
	}

	public void bindTexImageToPbuffer(PeerInfo handle, int buffer) {
		throw new UnsupportedOperationException();
	}

	public void releaseTexImageFromPbuffer(PeerInfo handle, int buffer) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Sets one or more icons for the Display.
	 * <ul>
	 * <li>On Windows you should supply at least one 16x16 icon and one 32x32.</li>
	 * <li>Linux (and similar platforms) expect one 32x32 icon.</li>
	 * <li>Mac OS X should be supplied one 128x128 icon</li>
	 * </ul>
	 * The implementation will use the supplied ByteBuffers with image data in RGBA and perform any conversions nescesarry for the specific platform.
	 *
	 * @param icons Array of icons in RGBA mode
	 * @return number of icons used.
	 */
	public int setIcon(ByteBuffer[] icons) {
		/*int size = 0;
		int biggest = -1;

		for (int i=0;i<icons.length;i++) {
			if (icons[i].remaining() > size) {
				biggest = i;
				size = icons[i].remaining();
			}
		}

		if (biggest == -1) {
			return 0;
		}

		int width;
		int height;

		IntBuffer biggest_icon = icons[biggest].asIntBuffer();
		int[] imageData = new int[biggest_icon.remaining()];
		width = height = (int) Math.sqrt(imageData.length);
		biggest_icon.get(imageData);

		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		img.setRGB(0, 0, width, height, imageData, 0, width);
		frame.setIconImage(img);

		return 1;*/
		// Don't use any icon, since Mac OS X windows don't have window icons
		return 0;
	}
	
	public int getX() {
		return nGetX(window);
	}

	public int getY() {
		return nGetY(window);
	}

	public int getWidth() {
		return nGetWidth(window);
	}
	
	public int getHeight() {
		return nGetHeight(window);
	}

	public boolean isInsideWindow() {
		return mouseInsideWindow;
	}

	public void setResizable(boolean resizable) {
		// The windowed native window is created resizable above. Calling the
		// stock native nSetResizable here mutates NSWindow from Minecraft's
		// render thread and aborts current macOS when the user starts a resize.
		// Keep this method side-effect free and let Cocoa handle edge dragging.
	}

	public boolean wasResized() {
		/*
		 * Display.update() calls this immediately after swapBuffers(). During a
		 * native drag this is the render-thread safe point that fences the old
		 * frame, waits for the newest prepared AppKit geometry, and refreshes the
		 * drawable before Minecraft consumes the new dimensions.
		 */
		boolean guardedResize = synchronizeLiveResizeStep();
		return nWasResized(window) || guardedResize;
	}

	/**
	 * One-shot exact-runtime acceptance hook used by the OPUS native UI test.
	 * Normal game code never calls this method; user-driven window resizing is
	 * still owned entirely by Cocoa. Keeping the hook here lets the acceptance
	 * run exercise the same nWasResized/update/context path as an edge drag.
	 */
	public void opusResizeWindowForVerification(int width, int height) {
		if (window == null || !native_mode) {
			throw new IllegalStateException("Native macOS window is not available");
		}
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Window dimensions must be positive");
		}
		nResizeWindowOnMainThread(window, nGetX(window), nGetY(window), width, height);
	}

	/**
	 * Applies a content/drawable resize correction after the acceptance probe
	 * has observed the native outer-to-content frame delta. Cocoa's legacy
	 * LWJGL bridge sizes the outer NSWindow, while Minecraft validates the
	 * drawable/content size; the delta is intentionally measured by the probe
	 * instead of being hard-coded for a particular title-bar theme.
	 */
	public void opusResizeWindowForVerificationContent(
			int contentWidth,
			int contentHeight,
			int observedWidth,
			int observedHeight) {
		if (contentWidth <= 0 || contentHeight <= 0 || observedWidth <= 0 || observedHeight <= 0) {
			throw new IllegalArgumentException("Resize dimensions must be positive");
		}
		int frameWidth = Math.max(0, contentWidth - observedWidth);
		int frameHeight = Math.max(0, contentHeight - observedHeight);
		opusResizeWindowForVerification(contentWidth + frameWidth, contentHeight + frameHeight);
	}
	
	public float getPixelScaleFactor() {
		return (enableHighDPI && !Display.isFullscreen()) ? scaleFactor : 1f;
	}

}
