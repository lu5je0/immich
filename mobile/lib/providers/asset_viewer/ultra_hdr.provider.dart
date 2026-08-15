import 'dart:async';

import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:immich_mobile/domain/models/asset/base_asset.model.dart';
import 'package:immich_mobile/extensions/platform_extensions.dart';
import 'package:immich_mobile/providers/infrastructure/platform.provider.dart';
import 'package:immich_mobile/providers/infrastructure/settings.provider.dart';
import 'package:immich_mobile/utils/image_url_builder.dart';

final ultraHdrSupportedProvider = FutureProvider<bool>((ref) async {
  if (!CurrentPlatform.isAndroid) {
    return false;
  }
  try {
    return await hdrImageApi.isUltraHdrSupported();
  } catch (_) {
    return false;
  }
});

int _nextRequestId = 0;

/// True while the viewer's PageView is actively scrolling. Reserved for
/// gating overlay work that must not run mid-swipe.
final viewerPageScrollingProvider = StateProvider.autoDispose<bool>((_) => false);

/// True while a route-less overlay (e.g. the kebab MenuAnchor) is open above
/// the viewer. Such overlays never change the route stack, so the native HDR
/// overlay must be told explicitly to yield beneath them.
final viewerMenuOpenProvider = StateProvider<bool>((_) => false);

/// Measured height of the viewer's bottom bar (gradient scrim included). The
/// native HDR overlay must leave exactly this strip unpainted; a fixed guess
/// would cover part of the scrim, which then flashes whenever the overlay
/// hides and re-shows around a page switch.
final viewerBottomBarHeightProvider = StateProvider<double>((_) => 0);

/// Resolves a native render source (content URI or cached file path) when the
/// asset is an Ultra HDR image, or null otherwise. Only watched for the
/// current viewer page, so fast page swipes cancel the request on dispose.
final ultraHdrSourceProvider = FutureProvider.autoDispose.family<String?, BaseAsset>((ref, asset) async {
  final enabled = ref.watch(appConfigProvider.select((config) => config.image.ultraHdr));
  if (!enabled || !asset.isImage || asset.isAnimatedImage || asset.isEdited) {
    return null;
  }

  final supported = await ref.watch(ultraHdrSupportedProvider.future);
  if (!supported) {
    return null;
  }

  final requestId = _nextRequestId++;
  var cancelled = false;
  ref.onDispose(() {
    cancelled = true;
    unawaited(hdrImageApi.cancelRequest(requestId));
  });

  // Debounce so quickly swiping past a page never triggers native work.
  await Future<void>.delayed(const Duration(milliseconds: 200));
  if (cancelled) {
    return null;
  }

  final useLocal = asset.hasLocal && (!asset.hasRemote || !ref.read(appConfigProvider).image.preferRemote);
  final remoteId = asset.remoteId;
  if (!useLocal && remoteId == null) {
    return null;
  }

  try {
    final source = await hdrImageApi.getUltraHdrSource(
      localId: useLocal ? asset.localId : null,
      remoteUrl: useLocal ? null : getOriginalUrlForRemoteId(remoteId!),
      requestId: requestId,
    );
    if (!cancelled) {
      ref.keepAlive();
    }
    return source;
  } catch (_) {
    return null;
  }
});
