import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/platform/hdr_image_api.g.dart',
    kotlinOut: 'android/app/src/main/kotlin/app/alextran/immich/hdr/HdrImages.g.kt',
    kotlinOptions: KotlinOptions(package: 'app.alextran.immich.hdr'),
    dartOptions: DartOptions(),
    dartPackageName: 'immich_mobile',
  ),
)
@HostApi()
abstract class HdrImageApi {
  bool isUltraHdrSupported();

  /// Returns a renderable source for the native Ultra HDR view when the asset
  /// carries a gain map: a content URI for local assets, or a cached file path
  /// for remote assets (downloaded from the original endpoint). Returns null
  /// when the asset is not an Ultra HDR image.
  @async
  String? getUltraHdrSource({String? localId, String? remoteUrl, required int requestId});

  void cancelRequest(int requestId);
}
