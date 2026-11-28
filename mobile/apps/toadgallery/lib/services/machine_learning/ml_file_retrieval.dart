import "dart:io";

import "package:toadgallery/module/download/download_error.dart";
import "package:toadgallery/services/machine_learning/ml_exceptions.dart";

typedef MlFileDownloadAndLoader = Future<File?> Function();

Future<File?> downloadAndLoadFileForMlWithDecryptionRetry(
  MlFileDownloadAndLoader downloadAndLoad,
) async {
  const retryCount = 3;
  DownloadDecryptionError? firstFailure;
  for (var attempt = 0; ; attempt++) {
    try {
      return await downloadAndLoad();
    } on DownloadDecryptionError catch (error) {
      firstFailure ??= error;
      if (error.encryptedFileSha1 != firstFailure.encryptedFileSha1) {
        rethrow;
      }
      if (attempt == retryCount) {
        throw RepeatedFileDecryptionError();
      }
    }
  }
}
