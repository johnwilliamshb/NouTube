package expo.modules.noutubeview

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Full flavor only: drives Google Cast (Chromecast / Google TV / Android TV)
// for the video the page is playing. The foss flavor ships a no-op stub with
// the same API (see src/foss), so callers never need flavor checks.
//
// Flow: the page's cast button calls castVideo(). When no TV is connected yet
// the request is parked and the system device picker opens; the session
// listener fires once the user picks a TV and the parked video starts there at
// the position it had on the phone. Tapping the button while connected sends
// the current video to the TV instead. Long-pressing it disconnects.
//
// All entry points are safe to call from a @JavascriptInterface thread: the
// Cast SDK calls are marshalled onto the main thread here.
internal class NouCast(private val view: NouTubeView) {
  private data class PendingCast(val videoUrl: String, val title: String, val positionMs: Long)

  private val mainHandler = Handler(Looper.getMainLooper())
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var pending: PendingCast? = null
  private var listenerRegistered = false

  @Volatile
  private var connectedFlag = false

  private val sessionListener = object : SessionManagerListener<CastSession> {
    override fun onSessionStarted(session: CastSession, sessionId: String) {
      connectedFlag = true
      val request = pending ?: return
      pending = null
      loadOnSession(session, request)
    }

    override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
      connectedFlag = true
    }

    override fun onSessionEnded(session: CastSession, error: Int) {
      connectedFlag = false
      pending = null
      toast("Disconnected from TV")
    }

    override fun onSessionStarting(session: CastSession) = Unit
    override fun onSessionStartFailed(session: CastSession, error: Int) {
      pending = null
    }
    override fun onSessionEnding(session: CastSession) = Unit
    override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
    override fun onSessionSuspended(session: CastSession, reason: Int) {
      connectedFlag = false
    }
  }

  // No CastContext touch: safe on any thread.
  fun isAvailable(): Boolean {
    return try {
      Class.forName("com.google.android.gms.cast.framework.CastContext")
      GoogleApiAvailability.getInstance()
        .isGooglePlayServicesAvailable(view.context) == ConnectionResult.SUCCESS
    } catch (e: Throwable) {
      false
    }
  }

  // Cached from the session listener: safe on any thread.
  fun isConnected(): Boolean = isAvailable() && connectedFlag

  fun castVideo(videoUrl: String, title: String, positionMs: Long) {
    mainHandler.post { castVideoOnMain(videoUrl, title, positionMs) }
  }

  fun showDevicePicker() {
    mainHandler.post { showDevicePickerOnMain() }
  }

  fun stopCasting() {
    mainHandler.post {
      val activity = view.currentActivity ?: return@post
      try {
        CastContext.getSharedInstance(activity).sessionManager.endCurrentSession(true)
      } catch (e: Exception) {
        // No session to end.
      }
    }
  }

  fun destroy() {
    scope.cancel()
  }

  private fun castVideoOnMain(videoUrl: String, title: String, positionMs: Long) {
    val activity = view.currentActivity ?: run {
      toast("Could not cast right now")
      return
    }
    if (!isAvailable()) {
      toast("Casting needs Google Play services")
      return
    }
    val session = currentSession(activity)
    if (session != null && session.isConnected) {
      loadOnSession(session, PendingCast(videoUrl, title, positionMs))
    } else {
      pending = PendingCast(videoUrl, title, positionMs)
      ensureListener(activity)
      showDevicePickerOnMain()
    }
  }

  private fun showDevicePickerOnMain() {
    val activity = view.currentActivity ?: return
    if (!isAvailable()) {
      toast("Casting needs Google Play services")
      return
    }
    ensureListener(activity)
    val selector = MediaRouteSelector.Builder()
      .addControlCategory(
        CastMediaControlIntent.categoryForCast(
          CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
        ),
      )
      .build()
    MediaRouteChooserDialog(activity).apply {
      routeSelector = selector
      show()
    }
  }

  private fun currentSession(activity: Activity): CastSession? {
    return try {
      CastContext.getSharedInstance(activity).sessionManager.currentCastSession
    } catch (e: Exception) {
      null
    }
  }

  private fun ensureListener(activity: Activity) {
    if (listenerRegistered) return
    try {
      CastContext.getSharedInstance(activity).sessionManager
        .addSessionManagerListener(sessionListener, CastSession::class.java)
      listenerRegistered = true
      // A session may already exist from before the listener was registered.
      connectedFlag = currentSession(activity)?.isConnected == true
    } catch (e: Exception) {
      toast("Could not start casting")
    }
  }

  private fun loadOnSession(session: CastSession, request: PendingCast) {
    toast("Preparing cast…")
    scope.launch(Dispatchers.IO) {
      // A direct stream URL is needed: the TV cannot play a YouTube watch page.
      // yt-dlp runs on the phone and the TV fetches the stream itself; both
      // are on the same network so the URL works from the TV too.
      val streamUrl = try {
        NouYtDlp(view.context).resolveStreamUrl(request.videoUrl, useCookies = true)
      } catch (e: Exception) {
        null
      }
      withContext(Dispatchers.Main) {
        if (streamUrl.isNullOrBlank()) {
          toast("This video can't be cast right now")
          return@withContext
        }
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
          putString(MediaMetadata.KEY_TITLE, request.title)
        }
        val mediaInfo = MediaInfo.Builder(streamUrl)
          .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
          .setContentType("video/mp4")
          .setMetadata(metadata)
          .build()
        val loadRequest = MediaLoadRequestData.Builder()
          .setMediaInfo(mediaInfo)
          .setAutoplay(true)
          .setCurrentTime((request.positionMs / 1000.0).coerceAtLeast(0.0))
          .build()
        val client = session.remoteMediaClient
        if (client == null) {
          toast("Lost connection to the TV")
          return@withContext
        }
        client.load(loadRequest).setResultCallback {
          toast("Casting to TV")
        }
      }
    }
  }

  private fun toast(message: String) {
    try {
      Toast.makeText(view.context, message, Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
      // Best effort only.
    }
  }
}
