package expo.modules.noutubeview

// FOSS flavor stub: no Play services, so no Google Cast. Same API as the full
// implementation (see src/full) so callers never need flavor checks.
internal class NouCast(
  @Suppress("UNUSED_PARAMETER") view: NouTubeView,
) {
  fun isAvailable(): Boolean = false

  fun isConnected(): Boolean = false

  fun castVideo(
    @Suppress("UNUSED_PARAMETER") videoUrl: String,
    @Suppress("UNUSED_PARAMETER") title: String,
    @Suppress("UNUSED_PARAMETER") positionMs: Long,
  ) = Unit

  fun showDevicePicker() = Unit

  fun stopCasting() = Unit

  fun destroy() = Unit
}
