import 'package:flutter/services.dart';

/// Bridge to the native fullscreen Ultra HDR overlay. The overlay lives in the
/// Android view hierarchy above FlutterView, entirely outside the Flutter
/// compositor: rendering is pure HWUI (which applies the gain map) and touch
/// input arrives natively, so viewing and zooming run at native frame rates
/// with zero involvement from Flutter's frame loop.
///
/// While the viewer's app bars are visible the overlay clips its canvas to the
/// region between them (driven by [setChrome]), letting the Flutter-drawn bars
/// show through. Native side owns pinches, double taps and all interaction
/// while zoomed; every other touch is forwarded verbatim to FlutterView.
/// Page swipes stay fully Flutter-driven: the overlay slides in lockstep via
/// [setSwipeOffset], keeping the outgoing photo at HDR brightness, and a newly
/// landed page fades in after a short beat.
abstract final class UltraHdrSession {
  static const MethodChannel _channel = MethodChannel('immich_ultra_hdr_overlay');
  static bool _installed = false;

  static void Function(bool zoomed)? onZoomChanged;
  static VoidCallback? onSingleTap;

  static void ensureInstalled() {
    if (_installed) {
      return;
    }
    _installed = true;
    _channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'zoomChanged':
          onZoomChanged?.call(call.arguments as bool);
        case 'singleTap':
          onSingleTap?.call();
      }
      return null;
    });
  }

  static Future<void> setSource(String source, int targetDimension) async {
    ensureInstalled();
    try {
      await _channel.invokeMethod('setSource', {'source': source, 'targetDim': targetDimension});
    } catch (_) {
      // Missing implementation (e.g. engine restart) must never break the viewer.
    }
  }

  static Future<void> clear() async {
    try {
      await _channel.invokeMethod('clear');
    } catch (_) {
      // Missing implementation must never break the viewer.
    }
  }

  static Future<void> setSwipeOffset(double offsetPx) async {
    try {
      await _channel.invokeMethod('setSwipeOffset', offsetPx);
    } catch (_) {
      // Missing implementation must never break the viewer.
    }
  }

  static Future<void> setChrome({required int top, required int bottom, required bool visible}) async {
    try {
      await _channel.invokeMethod('setChrome', {'top': top, 'bottom': bottom, 'visible': visible});
    } catch (_) {
      // Missing implementation must never break the viewer.
    }
  }
}
