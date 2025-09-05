#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

@interface RCT_EXTERN_MODULE(VerusLightClient, RCTEventEmitter<RCTBridgeModule>)

// Synchronizer
RCT_EXTERN_METHOD(initialize:(NSString *)seed
:(NSString *)wif
:(NSString *)extsk
:(NSInteger *)birthdayHeight
:(NSString *)alias
:(NSString *)networkName
:(NSString *)defaultHost
:(NSInteger *)defaultPort
:(BOOL *)newWallet
resolver:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(start:(NSString *)alias
resolver:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(stop:(NSString *)alias
resolver:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(deleteWallet:(NSString *)alias
resolver:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(getLatestNetworkHeight:(NSString *)alias
resolver:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(getBirthdayHeight:(NSString *)host
:(NSInteger *)port
resolver:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(getInfo:(NSString *)alias
resolver:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(getPrivateBalance:(NSString *)alias
resolver:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(getPrivateTransactions:(NSString *)alias
resolver:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(sendToAddress:(NSString *)alias
:(NSString *)zatoshi
:(NSString *)toAddress
:(NSString *)memo
:(NSString *)extsk
:(NSString *)mnemonicSeed
resolver:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(shieldFunds:(NSString *)alias
:(NSString *)seed
:(NSString *)memo
:(NSString *)threshold
resolver:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(rescan:(NSString *)alias
resolver:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject
)

// Derivation tool
RCT_EXTERN_METHOD(deriveViewingKey:(NSString *)extsk
:(NSString *)seed
:(NSString *)network
resolver:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(deriveUnifiedSpendingKey:(NSString *)extsk
:(NSString *)seed
:(NSString *)network
resolver:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(deriveSaplingSpendingKey:(NSString *)seed
:(NSString *)network
resolver:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(deriveShieldedAddress:(NSString *)extsk
:(NSString *)seed
:(NSString *)network
resolver:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject
)


RCT_EXTERN_METHOD(deriveUnifiedAddress:(NSString *)alias
resolver:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(deriveSaplingAddress:(NSString *)alias
resolver:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject
)

RCT_EXTERN_METHOD(isValidAddress:(NSString *)address
:(NSString *)network
resolver:(RCTPromiseResolveBlock)resolve
rejecter:(RCTPromiseRejectBlock)reject
)

// Events
RCT_EXTERN_METHOD(supportedEvents)

@end
