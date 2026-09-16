#import <Cocoa/Cocoa.h>
#include <cmath>
#include <cstdio>

int main() {
    @autoreleasepool {
        [NSApplication sharedApplication];
        NSScreen *screen = [NSScreen mainScreen];
        if (screen == nil) {
            std::fprintf(stderr, "W2 APPKIT METRICS: no main screen\n");
            return 2;
        }

        NSRect content = NSMakeRect(0.0, 0.0, 800.0, 600.0);
        NSWindow *window = [[NSWindow alloc]
                initWithContentRect:content
                          styleMask:NSWindowStyleMaskBorderless
                            backing:NSBackingStoreBuffered
                              defer:NO
                             screen:screen];
        if (window == nil || window.contentView == nil) {
            std::fprintf(stderr, "W2 APPKIT METRICS: window/content view unavailable\n");
            return 3;
        }

        NSView *view = window.contentView;
        NSRect logical = view.bounds;
        NSRect backing = [view convertRectToBacking:logical];
        const double scale = window.backingScaleFactor;
        const double logicalWidth = NSWidth(logical);
        const double logicalHeight = NSHeight(logical);
        const double framebufferWidth = NSWidth(backing);
        const double framebufferHeight = NSHeight(backing);

        const bool valid = logicalWidth > 0.0 && logicalHeight > 0.0
                && framebufferWidth > 0.0 && framebufferHeight > 0.0
                && scale >= 1.0
                && std::fabs(framebufferWidth / logicalWidth - scale) < 0.0001
                && std::fabs(framebufferHeight / logicalHeight - scale) < 0.0001;

        std::printf("logical=%.0fx%.0f framebuffer=%.0fx%.0f backingScale=%.2f\n",
                logicalWidth, logicalHeight, framebufferWidth, framebufferHeight, scale);
        [window close];

        if (!valid) {
            std::fprintf(stderr, "W2 APPKIT METRICS: contract mismatch\n");
            return 4;
        }
        std::printf("W2 APPKIT METRICS: ALL PASSED\n");
        return 0;
    }
}
