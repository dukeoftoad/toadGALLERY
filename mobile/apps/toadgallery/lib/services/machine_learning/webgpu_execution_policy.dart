import "dart:io" show Platform;

import "package:device_info_plus/device_info_plus.dart";
import "package:toadgallery/service_locator.dart"
    show flagService, isLocalGalleryMode;
import "package:toadgallery/utils/ram_check_util.dart";

final webGpuExecutionPolicy = WebGpuExecutionPolicy();

// Rust owns durable quarantine and GPU adapter compatibility.
class WebGpuExecutionPolicy {
  Future<int?>? _androidSdkFuture;

  Future<bool> isEligible() async {
    if (!Platform.isAndroid ||
        (!isLocalGalleryMode && !flagService.webGPUEnabled) ||
        !enoughRamForLocalIndexing) {
      return false;
    }
    final sdk = await (_androidSdkFuture ??= _readAndroidSdk());
    return sdk != null && sdk >= 31;
  }

  static Future<int?> _readAndroidSdk() async {
    try {
      return (await DeviceInfoPlugin().androidInfo).version.sdkInt;
    } catch (_) {
      return null;
    }
  }
}
