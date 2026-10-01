#if TF_AUTO_RETURN
#import "HostArbiterShim.h"
#import <objc/message.h>
#import <objc/runtime.h>

// Every read of the private arbiter happens here, inside @try. A private getter or key-value coding can raise an
// Objective-C exception after an iOS change, even when the object answers to the name, and Swift cannot catch one, so in
// the keyboard it would be a crash. Here any exception reads as nil (at worst the objects of that one read leak: ARC does
// not release them when an exception unwinds). Names are resolved at runtime, never linked.

id _Nullable TFArbiterClient(void) {
    @try {
        Class cls = NSClassFromString(@"_UIKeyboardArbiterClient") ?: NSClassFromString(@"UIKeyboardArbiterClient");
        SEL selector = NSSelectorFromString(@"automaticSharedArbiterClient");
        Method method = cls ? class_getClassMethod(cls, selector) : NULL;
        if (!method || method_getNumberOfArguments(method) != 2) { return nil; }
        char returnType[8] = {0};
        method_getReturnType(method, returnType, sizeof(returnType));
        if (returnType[0] != '@') { return nil; } // only an object can be read back
        return ((id (*)(id, SEL))objc_msgSend)(cls, selector);
    } @catch (...) {
        return nil;
    }
}

id _Nullable TFArbiterValue(id _Nullable object, NSString *key) {
    @try {
        if (!object || ![object respondsToSelector:NSSelectorFromString(key)]) { return nil; }
        return [object valueForKey:key];
    } @catch (...) {
        return nil;
    }
}

void TFArbiterCall(id _Nullable object, NSString *selectorName) {
    @try {
        SEL selector = NSSelectorFromString(selectorName);
        Method method = object ? class_getInstanceMethod(object_getClass(object), selector) : NULL;
        if (!method || method_getNumberOfArguments(method) != 2) { return; }
        char returnType[8] = {0};
        method_getReturnType(method, returnType, sizeof(returnType));
        char type = returnType[0];
        if (type != 'v' && type != 'B' && type != 'c' && type != '@') { return; } // never a struct: it needs another call
        ((void (*)(id, SEL))objc_msgSend)(object, selector);
    } @catch (...) {
    }
}
#endif
