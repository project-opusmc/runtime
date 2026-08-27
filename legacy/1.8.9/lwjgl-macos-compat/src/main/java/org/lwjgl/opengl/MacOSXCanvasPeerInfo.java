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

import java.awt.Canvas;
import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.nio.ByteBuffer;

import javax.swing.SwingUtilities;

import org.lwjgl.LWJGLException;

/**
 * LWJGL's stock macOS canvas peer. The native pixel-format selector must see
 * Minecraft's original request (including an unspecified color depth); adding
 * a synthetic 32-bit color requirement changes the request and can make the
 * current macOS OpenGL bridge reject it.
 */
abstract class MacOSXCanvasPeerInfo extends MacOSXPeerInfo {
	private static final String OPUS_COMPATIBILITY_MARKER =
			"OPUS_MODERN_MACOS_PIXEL_FORMAT_COMPAT_V2";
	private final AWTSurfaceLock awt_surface = new AWTSurfaceLock();
	public ByteBuffer window_handle;

	protected MacOSXCanvasPeerInfo(PixelFormat pixel_format, ContextAttribs attribs,
			boolean support_pbuffer) throws LWJGLException {
		// Deliberately pass Minecraft's request through unchanged.
		super(pixel_format, attribs, false, true, support_pbuffer, true);
	}

	protected void initHandle(Canvas component) throws LWJGLException {
		boolean forceCALayer = true;
		boolean autoResizable = true;
		String javaVersion = System.getProperty("java.version");

		if (javaVersion.startsWith("1.5") || javaVersion.startsWith("1.6")) {
			forceCALayer = false;
		} else if (javaVersion.startsWith("1.7")) {
			autoResizable = false;
		}

		Insets insets = getInsets(component);
		int top = insets != null ? insets.top : 0;
		int left = insets != null ? insets.left : 0;
		window_handle = nInitHandle(
				awt_surface.lockAndGetHandle(component),
				getHandle(),
				window_handle,
				forceCALayer,
				autoResizable,
				component.getX() - left,
				component.getY() - top);

		if (javaVersion.startsWith("1.7")) {
			addComponentListener(component);
			reSetLayerBounds(component, getHandle());
		}
	}

	private void addComponentListener(final Canvas component) {
		for (ComponentListener listener : component.getComponentListeners()) {
			if ("CanvasPeerInfoListener".equals(listener.toString())) {
				return;
			}
		}

		component.addComponentListener(new ComponentListener() {
			public void componentHidden(ComponentEvent event) {
			}

			public void componentMoved(ComponentEvent event) {
				reSetLayerBounds(component, getHandle());
			}

			public void componentResized(ComponentEvent event) {
				reSetLayerBounds(component, getHandle());
			}

			public void componentShown(ComponentEvent event) {
			}

			public String toString() {
				return "CanvasPeerInfoListener";
			}
		});
	}

	private static native ByteBuffer nInitHandle(
			ByteBuffer surface_buffer,
			ByteBuffer peer_info_handle,
			ByteBuffer window_handle,
			boolean forceCALayer,
			boolean autoResizable,
			int x,
			int y) throws LWJGLException;

	private static native void nSetLayerBounds(
			ByteBuffer peer_info_handle, int x, int y, int width, int height);

	private static void reSetLayerBounds(Canvas component, ByteBuffer peer_info_handle) {
		Component peer = SwingUtilities.getRoot(component);
		Point location = SwingUtilities.convertPoint(
				component.getParent(), component.getLocation(), peer);
		int x = (int) location.getX();
		int y = (int) location.getY();
		Insets insets = getInsets(component);
		x -= insets != null ? insets.left : 0;
		y -= insets != null ? insets.top : 0;
		y = peer.getHeight() - y - component.getHeight();
		nSetLayerBounds(peer_info_handle, x, y, component.getWidth(), component.getHeight());
	}

	protected void doUnlock() throws LWJGLException {
		awt_surface.unlock();
	}

	private static Insets getInsets(Canvas component) {
		Container rootPane = SwingUtilities.getRootPane(component);
		return rootPane != null ? rootPane.getInsets() : new Insets(0, 0, 0, 0);
	}
}
