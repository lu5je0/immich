class ImageConfig {
  final bool preferRemote;
  final bool loadOriginal;
  final bool ultraHdr;

  const ImageConfig({this.preferRemote = false, this.loadOriginal = false, this.ultraHdr = true});

  ImageConfig copyWith({bool? preferRemote, bool? loadOriginal, bool? ultraHdr}) => ImageConfig(
    preferRemote: preferRemote ?? this.preferRemote,
    loadOriginal: loadOriginal ?? this.loadOriginal,
    ultraHdr: ultraHdr ?? this.ultraHdr,
  );

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is ImageConfig &&
          other.preferRemote == preferRemote &&
          other.loadOriginal == loadOriginal &&
          other.ultraHdr == ultraHdr);

  @override
  int get hashCode => Object.hash(preferRemote, loadOriginal, ultraHdr);

  @override
  String toString() =>
      'ImageConfig(preferRemoteImage: $preferRemote, loadOriginal: $loadOriginal, ultraHdr: $ultraHdr)';
}
