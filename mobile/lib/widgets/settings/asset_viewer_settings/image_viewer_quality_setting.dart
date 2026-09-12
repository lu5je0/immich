import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:immich_mobile/extensions/platform_extensions.dart';
import 'package:immich_mobile/generated/translations.g.dart';
import 'package:immich_mobile/providers/app_settings.provider.dart';
import 'package:immich_mobile/providers/infrastructure/settings.provider.dart';
import 'package:immich_ui/immich_ui.dart';

class ImageViewerQualitySetting extends HookConsumerWidget {
  const ImageViewerQualitySetting({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isOriginal = useState(ref.watch(appConfigProvider).image.loadOriginal);
    useValueChanged<bool, void>(isOriginal.value, (_, _) {
      unawaited(ref.read(settingsProvider).write(.imageLoadOriginal, isOriginal.value));
    });

    final isUltraHdr = useState(ref.read(appConfigProvider).image.ultraHdr);
    useValueChanged<bool, void>(isUltraHdr.value, (_, _) {
      unawaited(ref.read(settingsProvider).write(.imageUltraHdr, isUltraHdr.value));
    });

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SettingGroupTitle(
          title: context.t.photos,
          icon: Icons.image_outlined,
          subtitle: context.t.setting_image_viewer_help,
        ),
        SettingsSwitchListTile(
          valueNotifier: isOriginal,
          title: context.t.setting_image_viewer_original_title,
          subtitle: context.t.setting_image_viewer_original_subtitle,
          onChanged: (_) => ref.invalidate(appSettingsServiceProvider),
        ),
        if (CurrentPlatform.isAndroid)
          SettingsSwitchListTile(
            valueNotifier: isUltraHdr,
            title: context.t.setting_image_viewer_ultra_hdr_title,
            subtitle: context.t.setting_image_viewer_ultra_hdr_subtitle,
          ),
      ],
    );
  }
}
