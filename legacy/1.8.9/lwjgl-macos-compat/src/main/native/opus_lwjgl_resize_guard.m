/*
 * Copyright (c) 2026 Polyfrost / OPUS contributors.
 *
 * Synchronizes Cocoa's native live-resize transaction with LWJGL 2's Java
 * render thread. Minecraft 1.8.9 renders off the AppKit main thread; modern
 * macOS implements legacy OpenGL over Metal and may otherwise replace the
 * drawable while a command encoder is active.
 */

#import <Cocoa/Cocoa.h>
#import <dispatch/dispatch.h>
#import <jni.h>
#import <objc/message.h>
#import <objc/runtime.h>

#include <stdint.h>
#include <stdio.h>
#include <stdbool.h>

__attribute__((used)) static const char OPUS_RESIZE_GUARD_MARKER[] =
        "OPUS_COCOA_LIVE_RESIZE_GUARD_V9_APPKIT_DRAWABLE_UPDATE";

static JavaVM *opus_jvm;
static BOOL opus_window_preserves_content_before_resize = YES;
static BOOL opus_window_preserve_content_captured = NO;

/* LWJGL 2's MacOSXWindowInfo from native/macosx/context.h. */
typedef struct {
    id window;
    NSRect display_rect;
    id view;
    id context;
    jobject jdisplay;
    jobject jmouse;
    jobject jkeyboard;
    jboolean fullscreen;
    jboolean undecorated;
    jboolean resizable;
    jboolean parented;
    jboolean enableFullscreenModeAPI;
    jboolean enableHighDPI;
    jboolean resized;
} OpusMacOSXWindowInfoPrefix;

static OpusMacOSXWindowInfoPrefix *opus_window_info(id view) {
    Ivar parent_ivar = class_getInstanceVariable([view class], "_parent");
    if (parent_ivar == NULL) {
        return NULL;
    }
    ptrdiff_t offset = ivar_getOffset(parent_ivar);
    return *(OpusMacOSXWindowInfoPrefix **)((uint8_t *)(void *)view + offset);
}

static void opus_call_display(id view, const char *method_name) {
    OpusMacOSXWindowInfoPrefix *window_info = opus_window_info(view);
    if (opus_jvm == NULL || window_info == NULL || window_info->jdisplay == NULL) {
        return;
    }

    JNIEnv *env = NULL;
    bool attached_here = false;
    jint status = (*opus_jvm)->GetEnv(opus_jvm, (void **)&env, JNI_VERSION_1_4);
    if (status == JNI_EDETACHED) {
        if ((*opus_jvm)->AttachCurrentThread(opus_jvm, (void **)&env, NULL) != JNI_OK) {
            return;
        }
        attached_here = true;
    } else if (status != JNI_OK) {
        return;
    }

    jclass display_class = (*env)->GetObjectClass(env, window_info->jdisplay);
    if (display_class != NULL) {
        jmethodID method = (*env)->GetMethodID(env, display_class, method_name, "()V");
        if (method != NULL) {
            (*env)->CallVoidMethod(env, window_info->jdisplay, method);
        }
        (*env)->DeleteLocalRef(env, display_class);
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    if (attached_here) {
        (*opus_jvm)->DetachCurrentThread(opus_jvm);
    }
}

static bool opus_call_display_boolean(id view, const char *method_name) {
    OpusMacOSXWindowInfoPrefix *window_info = opus_window_info(view);
    if (opus_jvm == NULL || window_info == NULL || window_info->jdisplay == NULL) {
        return false;
    }

    JNIEnv *env = NULL;
    bool attached_here = false;
    jint status = (*opus_jvm)->GetEnv(opus_jvm, (void **)&env, JNI_VERSION_1_4);
    if (status == JNI_EDETACHED) {
        if ((*opus_jvm)->AttachCurrentThread(opus_jvm, (void **)&env, NULL) != JNI_OK) {
            return false;
        }
        attached_here = true;
    } else if (status != JNI_OK) {
        return false;
    }

    bool result = false;
    jclass display_class = (*env)->GetObjectClass(env, window_info->jdisplay);
    if (display_class != NULL) {
        jmethodID method = (*env)->GetMethodID(env, display_class, method_name, "()Z");
        if (method != NULL) {
            result = (*env)->CallBooleanMethod(env, window_info->jdisplay, method) == JNI_TRUE;
        }
        (*env)->DeleteLocalRef(env, display_class);
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        result = false;
    }
    if (attached_here) {
        (*opus_jvm)->DetachCurrentThread(opus_jvm);
    }
    return result;
}

static void opus_call_super(id view, SEL selector) {
    struct objc_super super_info = {
        .receiver = view,
        .super_class = class_getSuperclass([view class]),
    };
    ((void (*)(struct objc_super *, SEL))objc_msgSendSuper)(&super_info, selector);
}

static void opus_call_super_set_frame_size(id view, SEL selector, NSSize size) {
    struct objc_super super_info = {
        .receiver = view,
        .super_class = class_getSuperclass([view class]),
    };
    ((void (*)(struct objc_super *, SEL, NSSize))objc_msgSendSuper)(
            &super_info,
            selector,
            size);
}

static NSWindow *opus_window_for_view(id view) {
    OpusMacOSXWindowInfoPrefix *window_info = opus_window_info(view);
    if (window_info == NULL || window_info->window == nil) {
        return nil;
    }
    return (NSWindow *)window_info->window;
}

/*
 * AppKit decides whether to preserve and scale a window's last composited
 * contents when a live-resize transaction begins. Setting this property from
 * viewWillStartLiveResize is too late for the first drag because AppKit may
 * already have selected the stale-content snapshot. The Java display calls
 * this JNI entry immediately after nCreateWindow; marshal the mutation to
 * AppKit's main queue so the window is configured before any user drag.
 */
static void opus_disable_window_content_preservation_for_window(NSWindow *window) {
    if (window == nil) {
        return;
    }
    window.preservesContentDuringLiveResize = NO;
}

/*
 * LWJGL's original windowResized: notification updates display_rect after
 * AppKit has finished changing the content view. During a live resize that
 * notification can be delivered after setFrameSize: returns. The Java
 * handshake must see the new content dimensions before it calls
 * NSOpenGLContext.update(), otherwise it can render one frame against the
 * previous drawable while AppKit is already presenting the new view size.
 */
static void opus_sync_display_rect(id view) {
    OpusMacOSXWindowInfoPrefix *window_info = opus_window_info(view);
    if (window_info == NULL) {
        return;
    }

    window_info->display_rect = [view frame];
    window_info->resized = JNI_TRUE;
}

/*
 * NSWindow defaults preservesContentDuringLiveResize to YES. AppKit then
 * scales the last composited contents while a native edge drag is changing
 * the frame. That is exactly the stretched intermediate image the user sees:
 * Minecraft has not yet rendered the new drawable, but the compositor is
 * showing a resized copy of the previous one.
 *
 * The render-thread handshake already fences the old frame before releasing
 * each native resize step. Disable AppKit's preservation for the duration of
 * that transaction so the window does not scale stale pixels while the render
 * thread refreshes the drawable.
 */
static void opus_disable_window_content_preservation(id view) {
    NSWindow *window = opus_window_for_view(view);
    if (window == nil) {
        return;
    }

    opus_window_preserves_content_before_resize =
            window.preservesContentDuringLiveResize;
    opus_window_preserve_content_captured = YES;
    window.preservesContentDuringLiveResize = NO;
}

static void opus_restore_window_content_preservation(id view) {
    NSWindow *window = opus_window_for_view(view);
    if (window == nil || !opus_window_preserve_content_captured) {
        return;
    }

    window.preservesContentDuringLiveResize =
            opus_window_preserves_content_before_resize;
    opus_window_preserve_content_captured = NO;
}

/*
 * NSWindow.preservesContentDuringLiveResize is not the only preservation
 * switch involved here. AppKit also asks the content NSView whether it
 * preserves its own contents. MacOSXOpenGLView inherits YES from NSView, so
 * disabling the window property alone still lets AppKit scale the last
 * composited OpenGL frame while the drawable catches up. That produces the
 * stretched intermediate image seen during a drag.
 *
 * Override the read-only NSView hook on the LWJGL view itself. The existing
 * render-thread handshake remains responsible for fencing GL and updating the
 * drawable; this hook only tells AppKit not to reuse a stale view snapshot.
 */
static BOOL opus_view_preserves_content_during_live_resize(id view, SEL selector) {
    (void)view;
    (void)selector;
    return NO;
}

static void opus_view_will_start_live_resize(id view, SEL selector) {
    opus_disable_window_content_preservation(view);
    opus_call_display(view, "doBeginLiveResize");
    opus_call_super(view, selector);
}

static void opus_view_did_end_live_resize(id view, SEL selector) {
    opus_call_super(view, selector);
    opus_call_display(view, "doEndLiveResize");
    opus_restore_window_content_preservation(view);
}

static void opus_set_frame_size(id view, SEL selector, NSSize size) {
    NSSize current_size = [view frame].size;
    if (![view inLiveResize] || NSEqualSizes(current_size, size)) {
        opus_call_super_set_frame_size(view, selector, size);
        return;
    }

    if (!opus_call_display_boolean(view, "doPrepareLiveResizeStep")) {
        // A timed-out render thread cannot safely surrender its drawable.
        // Leave the view at its last presented size instead of risking the
        // Metal/OpenGL crash that this guard exists to prevent.
        fprintf(stderr, "[OPUS/LWJGL] skipped an unprepared live-resize step\n");
        return;
    }

    opus_call_super_set_frame_size(view, selector, size);
    opus_sync_display_rect(view);
    /*
     * doPrepareLiveResizeStep has already fenced the previous frame and
     * released the render-thread context. Update the drawable on AppKit's
     * thread before returning from setFrameSize:, so the compositor never
     * presents a resized copy of the previous drawable. The render thread
     * reacquires the context after doCommitLiveResizeStep wakes it.
     */
    SEL open_gl_context_selector = sel_registerName("openGLContext");
    SEL update_selector = sel_registerName("update");
    id open_gl_context = ((id (*)(id, SEL))objc_msgSend)(
            view,
            open_gl_context_selector);
    if (open_gl_context != nil) {
        ((void (*)(id, SEL))objc_msgSend)(open_gl_context, update_selector);
    }
    opus_call_display(view, "doCommitLiveResizeStep");
}

static void opus_surface_needs_update_noop(id view, SEL selector, id notification) {
    (void)view;
    (void)selector;
    (void)notification;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    opus_jvm = vm;
    return JNI_VERSION_1_4;
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_opengl_MacOSXDisplay_nInstallLiveResizeGuard(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;

    Class view_class = objc_getClass("MacOSXOpenGLView");
    if (view_class == Nil) {
        fprintf(stderr, "[OPUS/LWJGL] MacOSXOpenGLView is unavailable\n");
        return JNI_FALSE;
    }

    SEL will_selector = sel_registerName("viewWillStartLiveResize");
    SEL did_selector = sel_registerName("viewDidEndLiveResize");
    SEL frame_size_selector = sel_registerName("setFrameSize:");
    Method frame_size_method = class_getInstanceMethod(view_class, frame_size_selector);
    if (frame_size_method == NULL) {
        fprintf(stderr, "[OPUS/LWJGL] setFrameSize: is unavailable\n");
        return JNI_FALSE;
    }
    BOOL installed_will = class_addMethod(
            view_class,
            will_selector,
            (IMP)opus_view_will_start_live_resize,
            "v@:");
    BOOL installed_did = class_addMethod(
            view_class,
            did_selector,
            (IMP)opus_view_did_end_live_resize,
            "v@:");
    BOOL installed_frame_size = class_addMethod(
            view_class,
            frame_size_selector,
            (IMP)opus_set_frame_size,
            method_getTypeEncoding(frame_size_method));
    if (!installed_will || !installed_did || !installed_frame_size) {
        fprintf(stderr, "[OPUS/LWJGL] live-resize hooks already occupied\n");
        return JNI_FALSE;
    }

    // Keep NSViewGlobalFrameDidChangeNotification from invoking
    // NSOpenGLContext.update() concurrently on AppKit's main thread. The
    // render thread performs one update after the live-resize handshake.
    Method surface_method = class_getInstanceMethod(
            view_class,
            sel_registerName("_surfaceNeedsUpdate:"));
    if (surface_method == NULL) {
        fprintf(stderr, "[OPUS/LWJGL] _surfaceNeedsUpdate: is unavailable\n");
        return JNI_FALSE;
    }
    method_setImplementation(surface_method, (IMP)opus_surface_needs_update_noop);

    SEL preserves_selector = sel_registerName("preservesContentDuringLiveResize");
    Method preserves_method = class_getInstanceMethod(view_class, preserves_selector);
    if (preserves_method == NULL) {
        fprintf(stderr, "[OPUS/LWJGL] preservesContentDuringLiveResize is unavailable\n");
        return JNI_FALSE;
    }
    class_replaceMethod(
            view_class,
            preserves_selector,
            (IMP)opus_view_preserves_content_during_live_resize,
            method_getTypeEncoding(preserves_method));
    fprintf(stderr, "[OPUS/LWJGL] OpenGL view live-resize content preservation disabled\n");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_opengl_MacOSXDisplay_nDisableLiveResizeContentPreservation(
        JNIEnv *env,
        jclass clazz,
        jobject window_handle) {
    (void)clazz;

    if (window_handle == NULL) {
        return;
    }

    OpusMacOSXWindowInfoPrefix *window_info =
            (OpusMacOSXWindowInfoPrefix *)(*env)->GetDirectBufferAddress(
                    env,
                    window_handle);
    if (window_info == NULL || window_info->window == nil) {
        return;
    }

    NSWindow *window = (NSWindow *)window_info->window;
    if ([NSThread isMainThread]) {
        opus_disable_window_content_preservation_for_window(window);
    } else {
        dispatch_sync(dispatch_get_main_queue(), ^{
            opus_disable_window_content_preservation_for_window(window);
        });
    }
}

/*
 * The acceptance probe deliberately exercises repeated window geometry
 * changes, but it invokes the hook from Minecraft's render thread. AppKit's
 * NSWindow frame APIs are main-thread-only on current macOS; calling the
 * stock LWJGL nResizeWindow entry directly from the render thread can abort
 * inside ViewBridge. Reproduce LWJGL's frame/update sequence on the main
 * queue for this proof-only entry point.
 */
static void opus_resize_window_for_verification(
        OpusMacOSXWindowInfoPrefix *window_info,
        jint x,
        jint y,
        jint width,
        jint height) {
    if (window_info == NULL || window_info->window == nil) {
        return;
    }

    NSRect frame = NSMakeRect((CGFloat)x, (CGFloat)y, (CGFloat)width, (CGFloat)height);
    window_info->display_rect = frame;
    [(NSWindow *)window_info->window setFrame:frame display:NO];
}

JNIEXPORT void JNICALL
Java_org_lwjgl_opengl_MacOSXDisplay_nResizeWindowOnMainThread(
        JNIEnv *env,
        jclass clazz,
        jobject window_handle,
        jint x,
        jint y,
        jint width,
        jint height) {
    (void)clazz;

    if (window_handle == NULL) {
        return;
    }

    OpusMacOSXWindowInfoPrefix *window_info =
            (OpusMacOSXWindowInfoPrefix *)(*env)->GetDirectBufferAddress(
                    env,
                    window_handle);
    if (window_info == NULL || window_info->window == nil) {
        return;
    }

    if ([NSThread isMainThread]) {
        opus_resize_window_for_verification(window_info, x, y, width, height);
    } else {
        dispatch_sync(dispatch_get_main_queue(), ^{
            opus_resize_window_for_verification(window_info, x, y, width, height);
        });
    }
}
