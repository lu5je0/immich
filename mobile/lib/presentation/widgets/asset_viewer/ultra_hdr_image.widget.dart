import 'dart:async';

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
  static bool _sourceShown = false;

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
    // Hold the HDR window color mode for the whole viewer session: pages without
    // an HDR photo (e.g. videos) must not schedule a mode exit, whose surface
    // reconfiguration hitches mid-playback. Released by [shutdown] on close.
    _safeInvoke('beginSession');
  }

  /// Ends the viewer session, letting the native side drop the HDR window
  /// color mode once no overlay source remains.
  static void shutdown() {
    if (!_installed) {
      return;
    }
    _installed = false;
    _sourceShown = false;
    _channel.setMethodCallHandler(null);
    _safeInvoke('endSession');
  }

  static void _safeInvoke(String method) {
    try {
      unawaited(_channel.invokeMethod(method).catchError((_) {}));
    } catch (_) {
      // Missing implementation (e.g. engine restart) must never break the viewer.
    }
  }

  static Future<void> setSource(String source, int targetDimension) async {
    ensureInstalled();
    _sourceShown = true;
    try {
      await _channel.invokeMethod('setSource', {'source': source, 'targetDim': targetDimension});
    } catch (_) {
      // Missing implementation (e.g. engine restart) must never break the viewer.
    }
  }

  static Future<void> clear() async {
    _sourceShown = false;
    try {
      await _channel.invokeMethod('clear');
    } catch (_) {
      // Missing implementation must never break the viewer.
    }
  }

  static double? _pendingSwipeOffset;
  static bool _sendingSwipeOffset = false;

  /// Coalesces bursts of scroll updates: while one message is in flight only
  /// the latest offset is kept, so the platform channel never floods the
  /// Android main thread mid-swipe. Dropped entirely when no HDR source is
  /// shown, so swipes around video/plain pages carry no channel traffic and
  /// no native invalidations.
  static void setSwipeOffset(double offsetPx) {
    if (!_sourceShown) {
      return;
    }
    _pendingSwipeOffset = offsetPx;
    if (_sendingSwipeOffset) {
      return;
    }
    _sendingSwipeOffset = true;
    unawaited(
      Future(() async {
        try {
          while (_pendingSwipeOffset != null) {
            final offset = _pendingSwipeOffset!;
            _pendingSwipeOffset = null;
            await _channel.invokeMethod('setSwipeOffset', offset);
          }
        } catch (_) {
          _pendingSwipeOffset = null;
          // Missing implementation must never break the viewer.
        } finally {
          _sendingSwipeOffset = false;
        }
      }),
    );
  }

  static Future<void> setChrome({required int top, required int bottom, required bool visible}) async {
    try {
      await _channel.invokeMethod('setChrome', {'top': top, 'bottom': bottom, 'visible': visible});
    } catch (_) {
      // Missing implementation must never break the viewer.
    }
  }
}
