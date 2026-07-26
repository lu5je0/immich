import 'package:immich_mobile/domain/utils/event_stream.dart';

// Timeline Events
class TimelineReloadEvent extends Event {
  const TimelineReloadEvent();
}

class ScrollToTopEvent extends Event {
  const ScrollToTopEvent();
}

class ScrollToDateEvent extends Event {
  final DateTime date;

  const ScrollToDateEvent(this.date);
}

/// Emitted by the asset viewer when the user swipes to another asset, so the
/// timeline grid beneath can keep that asset's tile laid out - both to land
/// the closing hero animation on the right tile and to leave the grid
/// positioned at the viewed photo. [scope] is the TimelineService driving the
/// viewer; only the grid sharing that service should react.
class ScrollToAssetIndexEvent extends Event {
  final Object scope;
  final int index;

  const ScrollToAssetIndexEvent(this.scope, this.index);
}

// Asset Viewer Events
class ViewerShowDetailsEvent extends Event {
  const ViewerShowDetailsEvent();
}

class ViewerReloadAssetEvent extends Event {
  const ViewerReloadAssetEvent();
}

class ViewerStackAssetDeletedEvent extends Event {
  final int stackIndex;

  const ViewerStackAssetDeletedEvent({required this.stackIndex});
}

// Multi-Select Events
class MultiSelectToggleEvent extends Event {
  final bool isEnabled;
  const MultiSelectToggleEvent(this.isEnabled);
}

// Map Events
class MapMarkerReloadEvent extends Event {
  const MapMarkerReloadEvent();
}
