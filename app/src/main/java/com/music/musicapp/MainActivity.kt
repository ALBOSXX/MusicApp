package com.music.musicapp

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.browse.MediaBrowser
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_STOP
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isNotEmpty
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.text.toFloat

class MainActivity : ComponentActivity() {

    enum class AnimDirection {
        FORWARD,
        BACKWARD,
        NONE
    }

    private var isAnimating = false

    private val screenCache = HashMap<Int, View>()

    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }

    private data class AudioMetadata(val sampleRate: Int, val bitrate: Int, val channels: Int)

    private fun extractAudioMetadata(context: Context, uri: Uri): AudioMetadata {
        var sampleRate = 0
        var bitrate = 0
        var channels = 0


        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        bitrate = format.getInteger(MediaFormat.KEY_BIT_RATE)
                    }
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            extractor.release()
        }


        if (bitrate == 0 || sampleRate == 0) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                if (bitrate == 0) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.let {
                        bitrate = it
                    }
                }
                if (sampleRate == 0) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()?.let {
                        sampleRate = it
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                retriever.release()
            }
        }

        return AudioMetadata(sampleRate, bitrate, channels)
    }

    fun getMusic(context: Context): List<MusicItem> {
        val mediaList = mutableListOf<MusicItem>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DURATION
        )

        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString()
        )

        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "Unknown"
                val date = cursor.getLong(dateCol)
                val size = cursor.getLong(sizeCol)
                val duration = cursor.getLong(durationCol)

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                )


                val audioMeta = extractAudioMetadata(context, contentUri)

                mediaList.add(
                    MusicItem(
                        id = id,
                        uri =  contentUri,
                        name = name,
                        size = size,
                        dateAdded = date,
                        duration = duration,
                        sampleRate = audioMeta.sampleRate,
                        bitrate = audioMeta.bitrate,
                        channels = audioMeta.channels
                    )
                )

                if (mediaList.size >= 100) {
                    break
                }
            }
        }
        return mediaList
    }



    private fun showScreen(layoutResId: Int, direction: AnimDirection): View? {
        val container = findViewById<ViewGroup>(R.id.screen_container) ?: return null
        val oldView = if (container.isNotEmpty()) container.getChildAt(container.childCount - 1) else null

        if (oldView?.getTag(R.id.current_layout_tag) == layoutResId) {
            return oldView
        }

        if (isAnimating) return oldView


        val newView = screenCache.getOrPut(layoutResId) {
            LayoutInflater.from(this).inflate(layoutResId, container, false).apply {
                setTag(R.id.current_layout_tag, layoutResId)
            }
        }

        (newView.parent as? ViewGroup)?.removeView(newView)

        isAnimating = true
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()

        newView.alpha = 1f
        when (direction) {
            AnimDirection.FORWARD -> newView.translationX = screenWidth
            AnimDirection.BACKWARD -> newView.translationX = -screenWidth
            AnimDirection.NONE -> newView.translationX = 0f
        }

        container.addView(newView)

        if (direction == AnimDirection.NONE || oldView == null) {
            newView.translationX = 0f
            isAnimating = false
            if (oldView != null) container.removeView(oldView)
            return newView
        }

        currentFocus?.let {
            it.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0)
        }


        newView.animate()
            .translationX(0f)
            .setDuration(300)
            .withEndAction { isAnimating = false }
            .start()

        oldView.animate()
            .translationX(if (direction == AnimDirection.FORWARD) -screenWidth * 0.25f else screenWidth)
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                container.removeView(oldView)
                oldView.alpha = 1f
                oldView.translationX = 0f
            }
            .start()

        return newView
    }

    private var mediaPlayer: MediaPlayer? = null
    private var playingItemUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        val objectMapper = ObjectMapper()

        enableEdgeToEdge()
        actionBar?.hide()
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = true

        recycler(AnimDirection.NONE)






    }

    private fun recycler(direction: AnimDirection){
        showScreen(R.layout.music_recycler, direction)

        val musicRecyclerView : RecyclerView = findViewById(R.id.musicRecyclerView)
        val root = findViewById<LinearLayout>(R.id.main)


        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )

            musicRecyclerView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + 8
            }

            insets
        }


        val perms = arrayListOf<String>()
        if(ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ){
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if(ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.READ_MEDIA_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ){
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        }

        val array: Array<String> = perms.toTypedArray()

        ActivityCompat.requestPermissions(
            this@MainActivity,
            array, 101
        )






        val list = getMusic(applicationContext)



        val mediaAdapter = MusicAdapter(list.toMutableList(), object : OnItemClickListener{
            override fun onItemClick(item: Any) {
                item as MusicItem
                player(list.indexOf(item), item, AnimDirection.FORWARD)
            }

        })
        musicRecyclerView.adapter = mediaAdapter
        musicRecyclerView.layoutManager = LinearLayoutManager(this@MainActivity)
    }

    private fun player(index : Int , item : MusicItem, direction: AnimDirection){
        WindowCompat.setDecorFitsSystemWindows(window, false)

        showScreen(R.layout.player, direction)
        val root = findViewById<ConstraintLayout>(R.id.rootLayout)
        val imm = root.context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(root.windowToken, 0)
        val bottomBar = findViewById<LinearLayout>(R.id.bottomBar)

        val topbar = findViewById<LinearLayout>(R.id.topBar)
        val defaultRecyclerBottomMargin = 30

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + 8
            }


            if (!isImeVisible) {
                bottomBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = navBars.bottom + 8
                }
            }

            insets
        }

        root.requestApplyInsets()

        val totalDuration = findViewById<TextView>(R.id.tvTotalDuration)

        val minutes = TimeUnit.MILLISECONDS.toMinutes(item.duration);
        val seconds = TimeUnit.MILLISECONDS.toSeconds(item.duration) % 60;


        val timeFormatted = String.format("%d:%02d", minutes, seconds);
        totalDuration.text = timeFormatted

        val play = findViewById<ImageButton>(R.id.btnPlay)
        play.setImageResource(R.drawable.ic_retro_stop)
        val tvAudioInfo = findViewById<TextView>(R.id.tvAudioInfo)

        val bitrateKbps = item.bitrate / 1000
        val bitrateText = "$bitrateKbps kbps"

        val sampleRateKhz = item.sampleRate / 1000.0
        val sampleRateText = "$sampleRateKhz kHz"

        val channelText = when (item.channels) {
            1 -> "Mono"
            2 -> "Stereo"
            else -> "${item.channels} Channels"
        }

        tvAudioInfo.text = "${channelText.uppercase()}  $bitrateText  $sampleRateText"

        val tvSongTitle = findViewById<TextView>(R.id.tvSongTitle)
        tvSongTitle.text = "$index. ${item.name}"

        play.setOnClickListener {
            if (playingItemUri == item.uri) {
                if(mediaPlayer?.isPlaying == true) {
                    play.setImageResource(R.drawable.ic_retro_play)

                    mediaPlayer?.pause()
                } else  {
                    play.setImageResource(R.drawable.ic_retro_stop)
                    mediaPlayer?.start()
                }


            } else {
                play.setImageResource(R.drawable.ic_retro_stop)

                mediaPlayer?.stop()
                mediaPlayer?.release()


                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(applicationContext, item.uri)
                    prepare()
                    start()


                    setOnCompletionListener {
                        release()
                        mediaPlayer = null
                        playingItemUri = null

                    }
                }


                playingItemUri = item.uri


            }
        }

        if (playingItemUri == item.uri && mediaPlayer?.isPlaying == true) {

            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            playingItemUri = null


        } else {

            mediaPlayer?.stop()
            mediaPlayer?.release()


            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(applicationContext, item.uri)
                prepare()
                start()


                setOnCompletionListener {
                    release()
                    mediaPlayer = null
                    playingItemUri = null

                }
            }


            playingItemUri = item.uri


        }

    }


}

