#if TF_AUTO_RETURN
#import <Foundation/Foundation.h>
#import <objc/runtime.h>

// Turns the private keyboard arbiter on as the keyboard loads, in our own code: without it the arbiter answers nothing.
// The switch has to be in place before the keyboard's own code first asks whether the arbiter is on, and an Objective-C
// constructor runs before any of that code. This is the riskiest code in the keyboard, since a crash here means the
// keyboard never shows. So it swaps only a method the class defines itself (never one it inherits: that would change
// the superclass and every other subclass too), only when the method has the shape we expect (a BOOL class method with
// no arguments), checks every step, and does no logging or I/O. Anything unexpected leaves the class untouched, and the
// app falls back to swipe back. It compiles to nothing in a build without TF_AUTO_RETURN.
static void TFTurnOnArbiter(Class cls) {
    if (!cls) { return; }
    Class meta = object_getClass(cls);
    if (!meta) { return; }
    SEL selector = NSSelectorFromString(@"enabled");
    unsigned int count = 0;
    Method *methods = class_copyMethodList(meta, &count); // the class's own class methods, none it inherits
    if (!methods) { return; }
    for (unsigned int i = 0; i < count; i++) {
        Method method = methods[i];
        if (method_getName(method) != selector) { continue; }
        // BOOL is encoded 'B' on arm64 and 'c' on Intel simulators; the two arguments are self and _cmd.
        char returnType[8] = {0};
        method_getReturnType(method, returnType, sizeof(returnType));
        if ((returnType[0] == 'B' || returnType[0] == 'c') && method_getNumberOfArguments(method) == 2) {
            IMP replacement = imp_implementationWithBlock(^BOOL(__unused id _self) { return YES; });
            if (replacement) { method_setImplementation(method, replacement); }
        }
        break;
    }
    free(methods);
}

__attribute__((constructor))
static void TFActivateHostArbiter(void) {
    // The reader tries both class names, so turn the arbiter on for whichever exists.
    TFTurnOnArbiter(NSClassFromString(@"_UIKeyboardArbiterClient"));
    TFTurnOnArbiter(NSClassFromString(@"UIKeyboardArbiterClient"));
}
#endif
