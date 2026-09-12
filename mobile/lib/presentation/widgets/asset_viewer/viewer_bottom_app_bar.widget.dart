import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:immich_mobile/presentation/widgets/asset_viewer/bottom_bar.widget.dart';
import 'package:immich_mobile/providers/asset_viewer/asset_viewer.provider.dart';
import 'package:immich_mobile/providers/asset_viewer/ultra_hdr.provider.dart';

class ViewerBottomAppBar extends ConsumerStatefulWidget {
  const ViewerBottomAppBar({super.key});

  @override
  ConsumerState<ViewerBottomAppBar> createState() => _ViewerBottomAppBarState();
}

class _ViewerBottomAppBarState extends ConsumerState<ViewerBottomAppBar> {
  final GlobalKey _barKey = GlobalKey();

  @override
  Widget build(BuildContext context) {
    final showingControls = ref.watch(assetViewerProvider.select((s) => s.showingControls));
    final double opacity =
        ref.watch(assetViewerProvider.select((s) => s.backgroundOpacity)) * (showingControls ? 1 : 0);

    // The native HDR overlay leaves the bottom bar strip (buttons + gradient
    // scrim) unpainted; report the real height so the clip matches exactly.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      final height = _barKey.currentContext?.size?.height ?? 0;
      final notifier = ref.read(viewerBottomBarHeightProvider.notifier);
      if (height > 0 && notifier.state != height) {
        notifier.state = height;
      }
    });

    return IgnorePointer(
      ignoring: opacity < 1.0,
      child: AnimatedOpacity(
        opacity: opacity,
        duration: Durations.short2,
        child: KeyedSubtree(key: _barKey, child: const ViewerBottomBar()),
      ),
    );
  }
}
