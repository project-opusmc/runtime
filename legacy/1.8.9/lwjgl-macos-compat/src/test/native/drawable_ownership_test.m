/* Exercises the installed hooks without creating a window or touching input. */
#import <Cocoa/Cocoa.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

#include "../../main/native/opus_lwjgl_resize_guard.m"

static int unsafe_context_calls;
static int view_focus_calls;

@interface OpusTestViewBase : NSObject
- (NSRect)frame;
- (void)setFrameSize:(NSSize)size;
- (void)lockFocus;
- (BOOL)preservesContentDuringLiveResize;
- (BOOL)inLiveResize;
@end
@implementation OpusTestViewBase
- (NSRect)frame { return NSMakeRect(0, 0, 854, 480); }
- (void)setFrameSize:(NSSize)size { (void)size; }
- (void)lockFocus { view_focus_calls++; }
- (BOOL)preservesContentDuringLiveResize { return YES; }
- (BOOL)inLiveResize { return NO; }
@end

/* Same selectors/parent ivar as the locked LWJGL native, not an actual window. */
@interface MacOSXOpenGLView : OpusTestViewBase {
    OpusMacOSXWindowInfoPrefix *_parent;
}
- (void)_surfaceNeedsUpdate:(id)notification;
- (void)update;
@end
@implementation MacOSXOpenGLView
- (void)_surfaceNeedsUpdate:(id)notification { (void)notification; [self update]; }
- (void)update { unsafe_context_calls++; }
@end

static void original_lwjgl_focus(id view, SEL selector) {
    (void)view;
    (void)selector;
    unsafe_context_calls++;
}

static void require(BOOL condition, const char *message) {
    if (!condition) {
        fprintf(stderr, "FAIL: %s\n", message);
        exit(1);
    }
}

typedef jboolean (*ConsumeDrawableUpdate)(JNIEnv *, jclass);

int main(void) {
    @autoreleasepool {
        // An unsupported native class must fail before installing even one
        // callback: Java must not enter a half-installed ownership protocol.
        Class view_class = objc_getClass("MacOSXOpenGLView");
        require(!opus_install_live_resize_guard_on_main_thread(),
                "missing own focus selector rejects unsupported ABI");
        require(opus_get_own_instance_method(view_class, @selector(viewWillStartLiveResize)) == NULL
                && opus_get_own_instance_method(view_class, @selector(viewDidEndLiveResize)) == NULL
                && opus_get_own_instance_method(view_class, @selector(setFrameSize:)) == NULL,
                "failed guard installation must leave resize callbacks untouched");
        require(class_addMethod(view_class, @selector(lockFocus), (IMP)original_lwjgl_focus, "v@:"),
                "complete the locked native selector fixture");
        require(opus_install_live_resize_guard_on_main_thread(), "hooks install");
        MacOSXOpenGLView *view = [MacOSXOpenGLView new];

        /* Fullscreen emits frame notifications outside an edge-drag interval. */
        atomic_store(&opus_live_resize_guard_active, false);
        [view _surfaceNeedsUpdate:nil];
        require(unsafe_context_calls == 0,
                "non-live/fullscreen notification must not update GL on AppKit");

        ConsumeDrawableUpdate consume = (ConsumeDrawableUpdate)dlsym(RTLD_DEFAULT,
                "Java_org_lwjgl_opengl_MacOSXDisplay_nConsumeDrawableUpdate");
        require(consume != NULL, "render thread can consume deferred drawable notification");
        require(consume(NULL, NULL), "ordinary notification is delivered to render thread");
        require(!consume(NULL, NULL), "one notification is consumed only once");

        atomic_store(&opus_live_resize_guard_active, true);
        [view _surfaceNeedsUpdate:nil];
        require(unsafe_context_calls == 0, "live-resize notification also defers GL");
        require(consume(NULL, NULL), "live notification is not lost");

        [view update];
        require(unsafe_context_calls == 0, "direct NSView update cannot mutate render context");
        require(consume(NULL, NULL), "direct view update is deferred");

        [view lockFocus];
        require(unsafe_context_calls == 0, "AppKit lockFocus cannot make game context current");
        require(view_focus_calls == 1, "NSView focus bookkeeping remains intact");
        require(consume(NULL, NULL), "focus notification is deferred without stealing GL ownership");

        for (int i = 0; i < 200; i++) [view _surfaceNeedsUpdate:nil];
        require(consume(NULL, NULL), "burst of notifications coalesces to pending work");
        require(!consume(NULL, NULL), "notification burst does not queue stale geometry updates");
        printf("PASS: drawable hooks defer ordinary/fullscreen/live/focus updates; one-shot coalescing\n");
        [view release];
    }
    return 0;
}
