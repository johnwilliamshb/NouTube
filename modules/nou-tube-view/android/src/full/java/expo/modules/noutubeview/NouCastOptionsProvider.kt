package expo.modules.noutubeview

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

// Full flavor only. Uses the Cast SDK's default media receiver, so no Cast
// developer-console registration is needed: any Chromecast / Google TV /
// Android TV on the same network can play the stream.
class NouCastOptionsProvider : OptionsProvider {
  override fun getCastOptions(context: Context): CastOptions =
    CastOptions.Builder()
      .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
      .build()

  override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
