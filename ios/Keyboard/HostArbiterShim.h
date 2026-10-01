// The keyboard's reads of the private keyboard arbiter, for HostArbiter.swift. This header is also the keyboard's Swift
// bridging header (project.yml, SWIFT_OBJC_BRIDGING_HEADER). Like everything that touches the private API, it holds
// nothing in a build without TF_AUTO_RETURN.
#if TF_AUTO_RETURN
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// The shared arbiter client, or nil when the class or its class method is not there or does not return an object.
id _Nullable TFArbiterClient(void);

/// `[object valueForKey:key]` when the object has a getter by that name, or nil.
id _Nullable TFArbiterValue(id _Nullable object, NSString *key);

/// Sends a selector that takes no argument and returns nothing, a BOOL or an object, and never reads the result.
void TFArbiterCall(id _Nullable object, NSString *selectorName);

NS_ASSUME_NONNULL_END
#endif
