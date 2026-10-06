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
#include <stddef.h>
#include <stdio.h>
#include <stdbool.h>
#include <stdatomic.h>
#include <stdlib.h>

__attribute__((used)) static const char OPUS_RESIZE_GUARD_MARKER[] =
        "OPUS_COCOA_LIVE_RESIZE_GUARD_V14_RENDER_THREAD_DRAWABLE_OWNERSHIP";

static JavaVM *opus_jvm;
static BOOL opus_window_preserves_content_before_resize = YES;
static BOOL opus_window_preserve_content_captured = NO;
static BOOL opus_emergency_live_resize_fallback = NO;
static atomic_bool opus_live_resize_guard_active = ATOMIC_VAR_INIT(false);
static atomic_bool opus_drawable_update_pending = ATOMIC_VAR_INIT(false);

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

/* Prefixes from the locked LWJGL 2.9.2 macOS context.h and JNI implementation. */
typedef struct {
    bool isCALayer;
    bool isWindowed;
    OpusMacOSXWindowInfoPrefix *window_info;
    id pixel_format;
    id pbuffer;
    id parent;
    id gl_layer;
} OpusMacOSXPeerInfoPrefix;

typedef struct {
    NSOpenGLContext *context;
    OpusMacOSXPeerInfoPrefix *peer_info;
} OpusMacOSXContextPrefix;

_Static_assert(offsetof(OpusMacOSXPeerInfoPrefix, window_info) == 8,
        "LWJGL 2.9.2 peer-info layout changed");
_Static_assert(offsetof(OpusMacOSXContextPrefix, peer_info) == 8,
        "LWJGL 2.9.2 context layout changed");

static char opus_bound_context_view_key;

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
 * The normal live-resize path deliberately avoids AppKit's scaled snapshot:
 * the render thread updates every prepared drawable. If that handshake cannot
 * run while Minecraft is booting, keeping the old view size produces a large
 * blank region on fullscreen. For that one exceptional transaction, ask
 * AppKit to scale the last completed frame instead. This keeps a visible,
 * coherent surface while avoiding an unsafe NSOpenGLContext.update() on the
 * AppKit thread; Java owns the first real drawable update after resize ends.
 */
static void opus_enable_emergency_content_preservation(id view) {
    NSWindow *window = opus_window_for_view(view);
    opus_emergency_live_resize_fallback = YES;
    if (window != nil) {
        window.preservesContentDuringLiveResize = YES;
    }
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
    return opus_emergency_live_resize_fallback ? YES : NO;
}

static void opus_view_will_start_live_resize(id view, SEL selector) {
    atomic_store_explicit(&opus_live_resize_guard_active, true, memory_order_release);
    opus_emergency_live_resize_fallback = NO;
    opus_disable_window_content_preservation(view);
    opus_call_display(view, "doBeginLiveResize");
    opus_call_super(view, selector);
}

static void opus_view_did_end_live_resize(id view, SEL selector) {
    opus_call_super(view, selector);
    opus_call_display(view, "doEndLiveResize");
    opus_emergency_live_resize_fallback = NO;
    opus_restore_window_content_preservation(view);
    /*
     * Do not release opus_live_resize_guard_active here. AppKit can finish
     * viewDidEndLiveResize before Minecraft reaches its next Display.update().
     * Java clears the guard only after the render thread has consumed the
     * final geometry and updated NSOpenGLContext itself.
     */
}

static void opus_set_frame_size(id view, SEL selector, NSSize size) {
    NSSize current_size = [view frame].size;
    if (![view inLiveResize] || NSEqualSizes(current_size, size)) {
        opus_call_super_set_frame_size(view, selector, size);
        return;
    }

    if (!opus_call_display_boolean(view, "doPrepareLiveResizeStep")) {
        bool bootstrap_fallback =
                opus_call_display_boolean(view, "doUseBootstrapLiveResizeFallback");
        bool emergency_fallback =
                opus_call_display_boolean(view, "doUseEmergencyLiveResizeFallback");
        if (bootstrap_fallback || emergency_fallback) {
            /*
             * Apply the initial fullscreen geometry instead of leaving the
             * AppKit view at its stale boot size. The bootstrap case has no
             * render-thread boundary at all. Apply only view geometry here;
             * the first subsequent Display.update() performs the drawable
             * update on Minecraft's render thread. The emergency case also
             * preserves a scaled frame until Java regains ownership.
             */
            if (emergency_fallback) {
                opus_enable_emergency_content_preservation(view);
            }
            opus_call_super_set_frame_size(view, selector, size);
            opus_sync_display_rect(view);
            return;
        }
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
     * released the render-thread context. AppKit now owns only the NSView
     * geometry change. doCommitLiveResizeStep wakes the render thread, which
     * reacquires and updates NSOpenGLContext before issuing more GL commands.
     */
    opus_call_display(view, "doCommitLiveResizeStep");
}

static void opus_defer_drawable_update(id view, SEL selector) {
    (void)view;
    (void)selector;
    // Fullscreen/backing changes also emit notifications outside inLiveResize.
    // Never let any AppKit callback update the render thread's GL context.
    // Coalesce them; Java consumes the newest geometry at its fenced boundary.
    atomic_store_explicit(&opus_drawable_update_pending, true, memory_order_release);
}

static void opus_surface_needs_update_guarded(id view, SEL selector, id notification) {
    (void)notification;
    opus_defer_drawable_update(view, selector);
}

static void opus_lock_focus_guarded(id view, SEL selector) {
    // LWJGL's own lockFocus additionally calls makeCurrentContext on AppKit.
    // Preserve NSView's focus bookkeeping, not that cross-thread GL mutation.
    opus_call_super(view, selector);
    opus_defer_drawable_update(view, selector);
}

static Method opus_get_own_instance_method(Class cls, SEL selector) {
    unsigned int count = 0;
    Method *methods = class_copyMethodList(cls, &count);
    Method result = NULL;
    for (unsigned int index = 0; index < count; ++index) {
        if (method_getName(methods[index]) == selector) {
            result = methods[index];
            break;
        }
    }
    free(methods);
    return result;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    opus_jvm = vm;
    return JNI_VERSION_1_4;
}

/*
 * Objective-C method-table changes that affect NSView must happen on
 * AppKit's main thread. Installing these hooks from Minecraft's render
 * thread can race AppKit's initial layer-tree enumeration and raise
 * NSFastEnumerationMutationHandler before the first frame is shown.
 */
static BOOL opus_install_live_resize_guard_on_main_thread(void) {
    Class view_class = objc_getClass("MacOSXOpenGLView");
    if (view_class == Nil) {
        fprintf(stderr, "[OPUS/LWJGL] MacOSXOpenGLView is unavailable\n");
        return NO;
    }

    SEL will_selector = sel_registerName("viewWillStartLiveResize");
    SEL did_selector = sel_registerName("viewDidEndLiveResize");
    SEL frame_size_selector = sel_registerName("setFrameSize:");
    Method frame_size_method = class_getInstanceMethod(view_class, frame_size_selector);
    if (frame_size_method == NULL) {
        fprintf(stderr, "[OPUS/LWJGL] setFrameSize: is unavailable\n");
        return NO;
    }
    // Validate every required selector before mutating any method table. An
    // unsupported/occupied class must not leave Java with a half-installed
    // resize handshake while resizeGuardInstalled remains false.
    SEL surface_selector = sel_registerName("_surfaceNeedsUpdate:");
    Method surface_method = opus_get_own_instance_method(view_class, surface_selector);
    Method update_method = opus_get_own_instance_method(view_class, sel_registerName("update"));
    Method focus_method = opus_get_own_instance_method(view_class, sel_registerName("lockFocus"));
    if (surface_method == NULL || update_method == NULL || focus_method == NULL) {
        fprintf(stderr, "[OPUS/LWJGL] drawable ownership selectors are unavailable\n");
        return NO;
    }
    SEL preserves_selector = sel_registerName("preservesContentDuringLiveResize");
    Method preserves_method = class_getInstanceMethod(view_class, preserves_selector);
    if (preserves_method == NULL) {
        fprintf(stderr, "[OPUS/LWJGL] preservesContentDuringLiveResize is unavailable\n");
        return NO;
    }
    if (opus_get_own_instance_method(view_class, will_selector) != NULL
            || opus_get_own_instance_method(view_class, did_selector) != NULL
            || opus_get_own_instance_method(view_class, frame_size_selector) != NULL) {
        fprintf(stderr, "[OPUS/LWJGL] live-resize hooks already occupied\n");
        return NO;
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
        return NO;
    }

    // Keep NSViewGlobalFrameDidChangeNotification from invoking
    // NSOpenGLContext.update() concurrently on AppKit's main thread. The
    // render thread performs one update after the live-resize handshake.
    method_setImplementation(surface_method, (IMP)opus_surface_needs_update_guarded);
    method_setImplementation(update_method, (IMP)opus_defer_drawable_update);
    method_setImplementation(focus_method, (IMP)opus_lock_focus_guarded);

    class_replaceMethod(
            view_class,
            preserves_selector,
            (IMP)opus_view_preserves_content_during_live_resize,
            method_getTypeEncoding(preserves_method));
    fprintf(stderr, "[OPUS/LWJGL] OpenGL view live-resize content preservation disabled\n");
    return YES;
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_opengl_MacOSXDisplay_nConsumeDrawableUpdate(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return atomic_exchange_explicit(&opus_drawable_update_pending, false,
            memory_order_acq_rel) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_opengl_MacOSXDisplay_nInstallLiveResizeGuard(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;

    __block BOOL installed = NO;
    if ([NSThread isMainThread]) {
        installed = opus_install_live_resize_guard_on_main_thread();
    } else {
        dispatch_sync(dispatch_get_main_queue(), ^{
            installed = opus_install_live_resize_guard_on_main_thread();
        });
    }
    return installed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_opengl_MacOSXDisplay_nCompleteLiveResizeGuard(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    atomic_store_explicit(&opus_live_resize_guard_active, false, memory_order_release);
}

/*
 * LWJGL 2's setView JNI creates and drains an autorelease pool on Minecraft's
 * render thread. On current AppKit that can release view objects during the
 * first context bind, and the same crash has occurred before any Opus UI runs.
 * Keep view attachment on AppKit's thread, while makeCurrentContext and all
 * GL commands stay on the render thread. The association avoids a main-queue
 * round trip every time LWJGL reacquires the unchanged view during a drag.
 */
JNIEXPORT jboolean JNICALL
Java_org_lwjgl_opengl_MacOSXDisplay_nBindContextView(
        JNIEnv *env,
        jclass clazz,
        jobject peer_handle,
        jobject context_handle,
        jboolean force) {
    (void)clazz;
    OpusMacOSXPeerInfoPrefix *peer_info =
            (OpusMacOSXPeerInfoPrefix *)(*env)->GetDirectBufferAddress(env, peer_handle);
    OpusMacOSXContextPrefix *context_info =
            (OpusMacOSXContextPrefix *)(*env)->GetDirectBufferAddress(env, context_handle);
    if (peer_info == NULL || context_info == NULL || !peer_info->isWindowed) {
        return JNI_FALSE; // Preserve LWJGL's original pbuffer and preview path.
    }

    NSOpenGLContext *context = context_info->context;
    id view = peer_info->window_info == NULL ? nil : peer_info->window_info->view;
    if (context == nil || view == nil) {
        jclass exception = (*env)->FindClass(env, "org/lwjgl/LWJGLException");
        if (exception != NULL) {
            (*env)->ThrowNew(env, exception, "Missing macOS OpenGL context or view");
        }
        return JNI_TRUE;
    }

    if (force != JNI_TRUE && objc_getAssociatedObject(context, &opus_bound_context_view_key) == view) {
        return JNI_TRUE;
    }

    void (^attach_view)(void) = ^{
        @autoreleasepool {
            if (force == JNI_TRUE || [context view] != view) {
                [context setView:view];
            }
            objc_setAssociatedObject(
                    context,
                    &opus_bound_context_view_key,
                    view,
                    OBJC_ASSOCIATION_ASSIGN);
        }
    };
    if ([NSThread isMainThread]) {
        attach_view();
    } else {
        dispatch_sync(dispatch_get_main_queue(), attach_view);
    }
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
