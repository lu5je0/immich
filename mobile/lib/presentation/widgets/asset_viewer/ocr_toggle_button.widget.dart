import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:immich_mobile/data/store.dart';
import 'package:immich_mobile/domain/models/asset/base_asset.model.dart';
import 'package:immich_mobile/extensions/build_context_extensions.dart';
import 'package:immich_mobile/providers/asset_viewer/asset_viewer.provider.dart';

class OcrToggleButton extends ConsumerStatefulWidget {
  final BaseAsset asset;
  const OcrToggleButton({super.key, required this.asset});

  @override
  ConsumerState<OcrToggleButton> createState() => _OcrToggleButtonState();
}

class _OcrToggleButtonState extends ConsumerState<OcrToggleButton> {
  // OCR data resolves async on every asset change; dropping to "no OCR"
  // while it loads makes the button blink out and back in mid-swipe. Keep the
  // last known state until the new asset's result actually arrives.
  bool _hasOcr = false;

  @override
  Widget build(BuildContext context) {
    final asset = widget.asset;
    if (asset is! RemoteAsset) {
      _hasOcr = false;
    } else {
      final ocr = ref.watch(Store.ocr.forAsset(asset.id));
      if (!ocr.isLoading) {
        _hasOcr = ocr.valueOrNull?.isNotEmpty == true;
      }
    }
    final showingOcr = ref.watch(assetViewerProvider.select((s) => s.showingOcr));

    return AnimatedSwitcher(
      duration: Durations.short4,
      child: !_hasOcr
          // Keep the button's footprint so the bottom bar (and its gradient
          // scrim) never changes height between assets.
          ? const SizedBox(height: 50)
          : Align(
              alignment: Alignment.centerRight,
              child: Padding(
                padding: const EdgeInsets.only(right: 32, bottom: 8),
                child: Material(
                  color: showingOcr ? context.primaryColor : Colors.black.withValues(alpha: 0.4),
                  shape: const CircleBorder(),
                  clipBehavior: Clip.antiAlias,
                  child: InkWell(
                    onTap: ref.watch(assetViewerProvider.notifier).toggleOcr,
                    child: const Padding(
                      padding: EdgeInsets.all(10.0),
                      child: Icon(Icons.text_fields_rounded, size: 22, color: Colors.white),
                    ),
                  ),
                ),
              ),
            ),
    );
  }
}
