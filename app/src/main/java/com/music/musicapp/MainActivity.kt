package com.music.musicapp

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.media.browse.MediaBrowser
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
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
import androidx.core.widget.addTextChangedListener
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

class MainActivity() : ComponentActivity() {

    enum class AnimDirection {
        FORWARD,
        BACKWARD,
        NONE
    }
    var animator : ObjectAnimator? = null

    var currentTimer: CountDownTimer? = null

    var visualizer: Visualizer? = null

    private var isAnimating = false
    var mediaList : MutableList<MusicItem>? = null

    private val screenCache = HashMap<Int, View>()

    private var mediaPlayer: MediaPlayer? = null
    private var playingItemUri: Uri? = null

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

    fun getMusic(context: Context, offset : Int?): MutableList<MusicItem> {
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


        val queryArgs = android.os.Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(
                android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                selectionArgs
            )
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 30)
            if(offset != null) {
                putInt(android.content.ContentResolver.QUERY_ARG_OFFSET, offset)
            }
        }


        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            queryArgs,
            null
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
            }
        }
        return mediaList.toMutableList()
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


    fun startRotation(view: View) {
        if(animator == null) {
            animator = ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 360f).apply {
                duration = 2000L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
            }
        }
        animator?.start()
    }

    fun stopRotation(){
        animator?.pause()
    }

    fun releaseVisualizer() {
        visualizer?.enabled = false
        visualizer?.release()
        visualizer = null
    }

    private fun recycler(direction: AnimDirection){
        showScreen(R.layout.music_recycler, direction)

        val musicRecyclerView : RecyclerView = findViewById(R.id.musicRecyclerView)
        val topBar : TextView = findViewById(R.id.topBarRecycler)
        val root = findViewById<LinearLayout>(R.id.main)


        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )

            topBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + 8
            }

            insets
        }

        releaseVisualizer()


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
        if(ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ){
            perms.add(Manifest.permission.RECORD_AUDIO)
        }

        val array: Array<String> = perms.toTypedArray()

        ActivityCompat.requestPermissions(
            this@MainActivity,
            array, 101
        )





        if(mediaList.isNullOrEmpty()){
            mediaList = getMusic(applicationContext, null)
        }



        val mediaAdapter = MusicAdapter(mediaList!!.toMutableList(), object : OnItemClickListener{
            override fun onItemClick(item: Any) {
                item as MusicItem
                player(mediaList!!.indexOf(item), item, AnimDirection.FORWARD)
            }

        })
        val manager = LinearLayoutManager(this@MainActivity)
        musicRecyclerView.adapter = mediaAdapter
        musicRecyclerView.layoutManager = manager
        var alreadyCalledForMoreElements = false
        var previousTotalItemCount = 0

        musicRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                if (dy <= 0) return

                val visibleItemCount = manager.childCount
                val totalItemCount = manager.itemCount
                val firstVisibleItemPosition = manager.findFirstVisibleItemPosition()

                val visibleThreshold = 30


                val nearEnd = (visibleItemCount + firstVisibleItemPosition) >= totalItemCount - visibleThreshold


                if (!alreadyCalledForMoreElements && nearEnd) {

                    if (totalItemCount > previousTotalItemCount) {
                        alreadyCalledForMoreElements = true
                        previousTotalItemCount = totalItemCount

                        Log.d("called", "for media total items $totalItemCount")

                        mediaList?.addAll(getMusic(applicationContext, manager.itemCount))
                    }
                }
            }
        })

        val searchEditText = findViewById<EditText>(R.id.searchEditText)
        searchEditText.addTextChangedListener(object : TextWatcher {

            public override fun afterTextChanged(s : Editable) {}
            override fun beforeTextChanged(
                p0: CharSequence?,
                p1: Int,
                p2: Int,
                p3: Int
            ) {
                TODO("Not yet implemented")
            }

            override fun onTextChanged(
                p0: CharSequence?,
                p1: Int,
                p2: Int,
                p3: Int
            ) {
                TODO("Not yet implemented")
            }

        });
    }

    fun formatTime(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format("%d:%02d", minutes, seconds)
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

            topbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
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

        val tvCurrentTime = findViewById<TextView>(R.id.tvCurrentTime)

        val songSeekBar = findViewById<SeekBar>(R.id.songSeekBar)

        songSeekBar.max = item.duration.toInt()

        fun startTimerFrom(durationMillis: Long) {
            currentTimer?.cancel()

            currentTimer = object : CountDownTimer(durationMillis, 1000) {
                override fun onTick(millisUntilFinished: Long) {

                    val alreadyMillis = item.duration - millisUntilFinished

                    tvCurrentTime.text = formatTime(alreadyMillis)
                    songSeekBar.progress = alreadyMillis.toInt()
                }

                override fun onFinish() {
                    tvCurrentTime.text = formatTime(item.duration)
                }
            }.start()
        }
        startTimerFrom(item.duration)


        val vizBars = listOf<View>(
            findViewById(R.id.viz1), findViewById(R.id.viz2), findViewById(R.id.viz3), findViewById(R.id.viz4),
            findViewById(R.id.viz5), findViewById(R.id.viz6), findViewById(R.id.viz7), findViewById(R.id.viz8),
            findViewById(R.id.viz9), findViewById(R.id.viz10), findViewById(R.id.viz11), findViewById(R.id.viz12),
            findViewById(R.id.viz13), findViewById(R.id.viz14), findViewById(R.id.viz15), findViewById(R.id.viz16),
            findViewById(R.id.viz17), findViewById(R.id.viz18), findViewById(R.id.viz19), findViewById(R.id.viz20)
        )

        fun updateVisualizerBars(fft: ByteArray) {


            vizBars.forEachIndexed { index, view ->

                val fftIndex = index * 2 + 2
                if (fftIndex < fft.size) {
                    val rfk = fft[fftIndex].toInt()
                    val ifk = fft[fftIndex + 1].toInt()
                    val magnitude = (Math.hypot(rfk.toDouble(), ifk.toDouble()) / 128.0).toFloat()


                    val maxHeightPx = 32.dpToPx(this@MainActivity)
                    val minHeightPx = 6.dpToPx(this@MainActivity)

                    var newHeight = (minHeightPx + (maxHeightPx - minHeightPx) * magnitude).toInt()
                    newHeight = newHeight.coerceIn(minHeightPx, maxHeightPx)


                    view.post {
                        val params = view.layoutParams
                        params.height = newHeight
                        view.layoutParams = params
                    }
                }
            }
        }

        fun setupVisualizer(audioSessionId: Int) {
            try {
                visualizer = Visualizer(audioSessionId).apply {

                    captureSize = Visualizer.getCaptureSizeRange()[1]

                    setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {

                        }

                        override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                            fft?.let { updateVisualizerBars(it) }
                        }
                    }, Visualizer.getMaxCaptureRate() / 2, false, true)

                    enabled = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }





        songSeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {

                        tvCurrentTime.text = formatTime(progress.toLong())
                    }
                }

                override fun onStartTrackingTouch(seek: SeekBar) {
                    currentTimer?.cancel()
                }

                override fun onStopTrackingTouch(seek: SeekBar) {

                    mediaPlayer?.seekTo(seek.progress)

                    if(mediaPlayer?.isPlaying == true) {


                        val remainingTime = item.duration - seek.progress


                        startTimerFrom(remainingTime)
                    }
                }
            }
        )

        val totalDuration = findViewById<TextView>(R.id.tvTotalDuration)

        totalDuration.text = formatTime(item.duration)


        val play = findViewById<ImageButton>(R.id.btnPlay)
        play.setImageResource(R.drawable.ic_retro_stop)
        val tvAudioInfo = findViewById<TextView>(R.id.tvAudioInfo)
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener {
            recycler(AnimDirection.BACKWARD)
        }

        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                recycler(AnimDirection.BACKWARD)
            }

        })

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

        val playerPfp = findViewById<ImageView>(R.id.playerPfp)
        startRotation(playerPfp)



        play.setOnClickListener {
            if (playingItemUri == item.uri) {
                if(mediaPlayer?.isPlaying == true) {
                    play.setImageResource(R.drawable.ic_retro_play)
                    stopRotation()
                    currentTimer?.cancel()
                    mediaPlayer?.pause()

                } else  {
                    play.setImageResource(R.drawable.ic_retro_stop)
                    startRotation(playerPfp)
                    currentTimer?.start()
                    mediaPlayer?.start()
                    val remainingTime = item.duration - songSeekBar.progress


                    startTimerFrom(remainingTime)
                }


            } else {
                play.setImageResource(R.drawable.ic_retro_stop)

                mediaPlayer?.stop()
                mediaPlayer?.release()
                stopRotation()


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
                startRotation(playerPfp)


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

        setupVisualizer(mediaPlayer?.audioSessionId ?: 1)

    }




}

