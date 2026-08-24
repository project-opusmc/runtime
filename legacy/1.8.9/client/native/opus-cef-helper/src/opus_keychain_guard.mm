// Process-level certificate-store guard for the Opus CEF helper.
//
// Chromium 151's macOS trust-store bridge observes and queries the system
// Keychain even when Chrome Root Store is enabled. Opus does not use macOS
// credentials or locally-installed roots, so those calls are deliberately
// denied before they can reach securityd. TLS verification remains enabled and
// uses Chromium's bundled public root store.

#include <Security/Security.h>

// The macOS SDK shipped on some developer machines omits the convenience
// header, but dyld still honors the documented __DATA,__interpose section.
#ifndef DYLD_INTERPOSE
#define DYLD_INTERPOSE(_replacement, _replace)                                  \
  __attribute__((used)) static const struct {                                  \
    const void* replacement;                                                     \
    const void* replace;                                                         \
  } _opus_interpose_##_replace __attribute__((section("__DATA,__interpose"))) = \
      {(const void*)(unsigned long)&_replacement,                                \
       (const void*)(unsigned long)&_replace}
#endif

extern "C" OSStatus OpusSecKeychainAddCallback(
    SecKeychainCallback callback,
    SecKeychainEventMask event_mask,
    void* context) {
  (void)callback;
  (void)event_mask;
  (void)context;
  // Chromium treats a registration error as a noisy certificate-store error.
  // Report success for this local no-op so the browser does not emit a false
  // failure, while still ensuring that no callback reaches securityd.
  return errSecSuccess;
}

// Chromium also asks macOS to validate the helper's ad-hoc code signature.
// On this machine that validation falls through to Security's code-signing
// service, which opens login.keychain-db even though Opus has no signing or
// credential requirement.  Return the same "unsigned" result that an
// unsigned development binary would produce, without entering Security.
extern "C" OSStatus OpusSecCodeCopySelf(SecCSFlags flags, SecCodeRef* self) {
  (void)flags;
  if (self) {
    *self = nullptr;
  }
  return errSecCSUnsigned;
}

extern "C" OSStatus OpusSecCodeCopyGuestWithAttributes(
    SecCodeRef host,
    CFDictionaryRef attributes,
    SecCSFlags flags,
    SecCodeRef* guest) {
  (void)host;
  (void)attributes;
  (void)flags;
  if (guest) {
    *guest = nullptr;
  }
  return errSecCSUnsigned;
}

extern "C" OSStatus OpusSecCodeCheckValidity(
    SecCodeRef code,
    SecCSFlags flags,
    SecRequirementRef requirement) {
  (void)code;
  (void)flags;
  (void)requirement;
  return errSecCSUnsigned;
}

extern "C" OSStatus OpusSecCodeCheckValidityWithErrors(
    SecCodeRef code,
    SecCSFlags flags,
    SecRequirementRef requirement,
    CFErrorRef* errors) {
  (void)code;
  (void)flags;
  (void)requirement;
  if (errors) {
    *errors = nullptr;
  }
  return errSecCSUnsigned;
}

extern "C" OSStatus OpusSecCodeCopyDesignatedRequirement(
    SecStaticCodeRef code,
    SecCSFlags flags,
    SecRequirementRef* requirement) {
  (void)code;
  (void)flags;
  if (requirement) {
    *requirement = nullptr;
  }
  return errSecCSUnsigned;
}

extern "C" OSStatus OpusSecCodeCopySigningInformation(
    SecStaticCodeRef code,
    SecCSFlags flags,
    CFDictionaryRef* information) {
  (void)code;
  (void)flags;
  if (information) {
    *information = nullptr;
  }
  return errSecCSUnsigned;
}

extern "C" OSStatus OpusSecStaticCodeCreateWithPath(
    CFURLRef path,
    SecCSFlags flags,
    SecStaticCodeRef* static_code) {
  (void)path;
  (void)flags;
  if (static_code) {
    *static_code = nullptr;
  }
  return errSecCSUnsigned;
}

extern "C" OSStatus OpusSecStaticCodeCreateWithPathAndAttributes(
    CFURLRef path,
    SecCSFlags flags,
    CFDictionaryRef attributes,
    SecStaticCodeRef* static_code) {
  (void)path;
  (void)flags;
  (void)attributes;
  if (static_code) {
    *static_code = nullptr;
  }
  return errSecCSUnsigned;
}

extern "C" OSStatus OpusSecStaticCodeCheckValidity(
    SecStaticCodeRef static_code,
    SecCSFlags flags,
    SecRequirementRef requirement) {
  (void)static_code;
  (void)flags;
  (void)requirement;
  return errSecCSUnsigned;
}

extern "C" OSStatus OpusSecStaticCodeCheckValidityWithErrors(
    SecStaticCodeRef static_code,
    SecCSFlags flags,
    SecRequirementRef requirement,
    CFErrorRef* errors) {
  (void)static_code;
  (void)flags;
  (void)requirement;
  if (errors) {
    *errors = nullptr;
  }
  return errSecCSUnsigned;
}

extern "C" OSStatus OpusSecKeychainCopyDefault(SecKeychainRef* keychain) {
  if (keychain) {
    *keychain = nullptr;
  }
  return errSecItemNotFound;
}

extern "C" OSStatus OpusSecKeychainGetStatus(
    SecKeychainRef keychain,
    SecKeychainStatus* status) {
  (void)keychain;
  if (status) {
    *status = 0;
  }
  return errSecNotAvailable;
}

extern "C" OSStatus OpusSecKeychainGetUserInteractionAllowed(Boolean* allowed) {
  if (allowed) {
    *allowed = false;
  }
  return errSecSuccess;
}

extern "C" OSStatus OpusSecKeychainSetUserInteractionAllowed(Boolean allowed) {
  (void)allowed;
  return errSecSuccess;
}

extern "C" OSStatus OpusSecKeychainFindGenericPassword(
    CFTypeRef keychain_or_array,
    UInt32 service_name_length,
    const char* service_name,
    UInt32 account_name_length,
    const char* account_name,
    UInt32* password_length,
    void** password_data,
    SecKeychainItemRef* item_ref) {
  (void)keychain_or_array;
  (void)service_name_length;
  (void)service_name;
  (void)account_name_length;
  (void)account_name;
  if (password_length) {
    *password_length = 0;
  }
  if (password_data) {
    *password_data = nullptr;
  }
  if (item_ref) {
    *item_ref = nullptr;
  }
  return errSecItemNotFound;
}

extern "C" OSStatus OpusSecKeychainCopyDomainDefault(
    SecPreferencesDomain domain,
    SecKeychainRef* keychain) {
  (void)domain;
  if (keychain) {
    *keychain = nullptr;
  }
  return errSecItemNotFound;
}

extern "C" OSStatus OpusSecTrustSettingsCopyCertificates(
    SecTrustSettingsDomain domain,
    CFArrayRef* certificates) {
  (void)domain;
  if (certificates) {
    *certificates = nullptr;
  }
  return errSecNoTrustSettings;
}

extern "C" OSStatus OpusSecTrustSettingsCopyTrustSettings(
    SecCertificateRef certificate,
    SecTrustSettingsDomain domain,
    CFArrayRef* trust_settings) {
  (void)certificate;
  (void)domain;
  if (trust_settings) {
    *trust_settings = nullptr;
  }
  return errSecItemNotFound;
}

extern "C" OSStatus OpusSecItemCopyMatching(CFDictionaryRef query,
                                              CFTypeRef* result) {
  (void)query;
  if (result) {
    *result = nullptr;
  }
  return errSecItemNotFound;
}

extern "C" OSStatus OpusSecKeychainCopySearchList(CFArrayRef* search_list) {
  if (search_list) {
    *search_list = nullptr;
  }
  return errSecNotAvailable;
}

DYLD_INTERPOSE(OpusSecKeychainAddCallback, SecKeychainAddCallback);
DYLD_INTERPOSE(OpusSecCodeCopySelf, SecCodeCopySelf);
DYLD_INTERPOSE(OpusSecCodeCopyGuestWithAttributes, SecCodeCopyGuestWithAttributes);
DYLD_INTERPOSE(OpusSecCodeCheckValidity, SecCodeCheckValidity);
DYLD_INTERPOSE(OpusSecCodeCheckValidityWithErrors, SecCodeCheckValidityWithErrors);
DYLD_INTERPOSE(OpusSecCodeCopyDesignatedRequirement,
               SecCodeCopyDesignatedRequirement);
DYLD_INTERPOSE(OpusSecCodeCopySigningInformation, SecCodeCopySigningInformation);
DYLD_INTERPOSE(OpusSecStaticCodeCreateWithPath, SecStaticCodeCreateWithPath);
DYLD_INTERPOSE(OpusSecStaticCodeCreateWithPathAndAttributes,
               SecStaticCodeCreateWithPathAndAttributes);
DYLD_INTERPOSE(OpusSecStaticCodeCheckValidity, SecStaticCodeCheckValidity);
DYLD_INTERPOSE(OpusSecStaticCodeCheckValidityWithErrors,
               SecStaticCodeCheckValidityWithErrors);
DYLD_INTERPOSE(OpusSecKeychainCopyDefault, SecKeychainCopyDefault);
DYLD_INTERPOSE(OpusSecKeychainGetStatus, SecKeychainGetStatus);
DYLD_INTERPOSE(OpusSecKeychainGetUserInteractionAllowed,
               SecKeychainGetUserInteractionAllowed);
DYLD_INTERPOSE(OpusSecKeychainSetUserInteractionAllowed,
               SecKeychainSetUserInteractionAllowed);
DYLD_INTERPOSE(OpusSecKeychainFindGenericPassword,
               SecKeychainFindGenericPassword);
DYLD_INTERPOSE(OpusSecKeychainCopyDomainDefault,
               SecKeychainCopyDomainDefault);
DYLD_INTERPOSE(OpusSecTrustSettingsCopyCertificates,
               SecTrustSettingsCopyCertificates);
DYLD_INTERPOSE(OpusSecTrustSettingsCopyTrustSettings,
               SecTrustSettingsCopyTrustSettings);
DYLD_INTERPOSE(OpusSecItemCopyMatching, SecItemCopyMatching);
DYLD_INTERPOSE(OpusSecKeychainCopySearchList, SecKeychainCopySearchList);
