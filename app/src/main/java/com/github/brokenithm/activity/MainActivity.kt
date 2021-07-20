package com.github.brokenithm.activity

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.*
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.brokenithm.BrokenithmApplication
import com.github.brokenithm.R
import com.github.brokenithm.util.AsyncTaskUtil
import com.github.brokenithm.util.FeliCa
import net.cachapa.expandablelayout.ExpandableLayout
import java.net.*
import java.nio.ByteBuffer
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var senderTask: AsyncTaskUtil.AsyncTask<InetSocketAddress?, Unit, Unit>
    private lateinit var receiverTask: AsyncTaskUtil.AsyncTask<InetSocketAddress?, Unit, Unit>
    private lateinit var pingPongTask: AsyncTaskUtil.AsyncTask<Unit, Unit, Unit>
    private var mExitFlag = true
    private lateinit var app: BrokenithmApplication
    private val serverPort = 52468
    private val mAirIdx = listOf(4, 5, 2, 3, 0, 1)

    // TCP
    private var mTCPMode = false
    private lateinit var mTCPSocket : Socket

    // state
    private val numOfButtons = 16
    private val numOfGaps = 16
    private val buttonWidthToGap = 7.428571f
    private val numOfAirBlock = 6
    private var mEnableTouchSize = false
    private var mFatTouchSizeThreshold = 0.027f
    private var mExtraFatTouchSizeThreshold = 0.035f
    private var mCurrentDelay = 0f

    // Buttons
    private var mCurrentAirHeight = 6
    private var mLastButtons = HashSet<Int>()
    private var mTestButton = false
    private var mServiceButton = false
    private data class InputEvent(val keys: MutableSet<Int>? = null, val airHeight : Int = 6, val testButton: Boolean = false, val serviceButton: Boolean = false)
    //private var mInputQueue = ArrayDeque<InputEvent>()

    // LEDs
    private lateinit var mLEDBitmap: Bitmap
    private lateinit var mLEDCanvas: Canvas
    private var buttonWidth = 0f
    private var gapWidth = 0f
    private lateinit var mButtonRenderer: View

    // vibrator
    private var mEnableVibrate = true
    private lateinit var vibrator: Vibrator
    private lateinit var vibratorTask: AsyncTaskUtil.AsyncTask<Unit, Unit, Unit>
    private lateinit var vibrateMethod: (Long) -> Unit
    private val vibrateLength = 50L
    private val mVibrationQueue = ArrayDeque<Long>()

    // view
    private var mEnableAir = true
    private var mAirSource = 3
    private var mSimpleAir = false
    private var mDebugInfo = false
    private var mShowDelay = false
    private lateinit var mDelayText: TextView
    private var windowWidth = 0f
    private var windowHeight = 0f
    private var mTouchAreaRect: Rect? = null

    // sensor
    private var mSensorManager: SensorManager? = null
    private var mSensorCallback: ((Float) -> Unit)? = null
    private var mGyroLowestBound = 0.8f
    private var mGyroHighestBound = 1.35f
    private var mAccelThreshold = 2f
    private val listener = object : SensorEventListener {
        var current = 0
        var lastAcceleration = 0f

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            return
        }

        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            val threshold = mAccelThreshold
            when (event.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    if (mAirSource != 2)
                        return
                    if (lastAcceleration != 0f) {
                        val accelX = (lastAcceleration - event.values[0])
                        if (accelX >= threshold && current >= 0)
                            current --
                        else if (accelX <= -threshold && current <= 0)
                            current ++
                        if (current > 0)
                            mCurrentAirHeight = 0
                        else if (current < 0)
                            mCurrentAirHeight = 6
                    }
                    lastAcceleration = event.values[0]
                }
                Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                    if (mAirSource != 1)
                        return
                    val rotationVector = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotationVector, event.values)
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(rotationVector, orientation)
                    mSensorCallback?.invoke(orientation[2])
                    return
                }
            }
        }
    }

    // nfc
    private fun Byte.getBit(bit: Int) = (toInt() ushr bit) and 0x1
    private fun MifareClassic.authenticateBlock(blockIndex: Int, keyA: ByteArray, keyB: ByteArray, write: Boolean = false): Boolean {
        // check access bits
        val sectorIndex = blockToSector(blockIndex)
        val accessBitsBlock = sectorToBlock(sectorIndex) + 3
        if (!authenticateSectorWithKeyA(sectorIndex, keyA)) return false
        val accessBits = readBlock(accessBitsBlock)
        val targetBit = blockIndex % 4
        val bitC1 = accessBits[7].getBit(targetBit + 4)
        val bitC2 = accessBits[8].getBit(targetBit)
        val bitC3 = accessBits[8].getBit(targetBit + 4)
        val allBits = (bitC1 shl 2) or (bitC2 shl 1) or bitC3
        return if (write) {
            when (allBits) {
                0 -> true
                3, 4, 6 -> authenticateSectorWithKeyB(sectorIndex, keyB)
                else -> false
            }
        } else {
            when (allBits) {
                7 -> false
                3, 5 -> authenticateSectorWithKeyB(sectorIndex, keyB)
                else -> true
            }
        }
    }
    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
    enum class CardType {
        CARD_AIME, CARD_FELICA
    }
    private var adapter: NfcAdapter? = null
    private val mAimeKey = byteArrayOf(0x57, 0x43, 0x43, 0x46, 0x76, 0x32)
    private var mEnableNFC = true
    private var hasCard = false
    private var cardType = CardType.CARD_AIME
    private val cardId = ByteArray(10)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG) ?: return
        val felica = FeliCa.get(tag)
        if (felica != null) {
            thread {
                try {
                    felica.connect()
                    felica.poll()
                    felica.IDm?.copyInto(cardId) ?: throw IllegalStateException("Failed to fetch IDm from FeliCa")
                    cardId[8] = 0
                    cardId[9] = 0
                    cardType = CardType.CARD_FELICA
                    hasCard = true
                    Log.d(TAG, "Found FeliCa card: ${cardId.toHexString().removeRange(16..19)}")
                    while (felica.isConnected) Thread.sleep(50)
                    hasCard = false
                    felica.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return
        }
        val mifare = MifareClassic.get(tag) ?: return
        thread {
            try {
                mifare.connect()
                if (mifare.authenticateBlock(2, keyA = mAimeKey, keyB = mAimeKey)) {
                    Thread.sleep(100)
                    val block = mifare.readBlock(2)
                    block.copyInto(cardId, 0, 6, 16)
                    cardType = CardType.CARD_AIME
                    hasCard = true
                    Log.d(TAG, "Found Aime card: ${cardId.toHexString()}")
                    while (mifare.isConnected) Thread.sleep(50)
                    hasCard = false
                } else {
                    Log.d(TAG, "NFC auth failed")
                }
                mifare.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setImmersive()
        app = application as BrokenithmApplication
        vibrator = applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val nfcManager = getSystemService(Context.NFC_SERVICE) as NfcManager
        adapter = nfcManager.defaultAdapter

        vibrateMethod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            {
                vibrator.vibrate(VibrationEffect.createOneShot(it, 255))
            }
        } else {
            {
                vibrator.vibrate(it)
            }
        }

        val settings = findViewById<Button>(R.id.button_settings)
        settings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        mDelayText = findViewById(R.id.text_delay)

        val editServer = findViewById<EditText>(R.id.edit_server).apply {
            setText(app.lastServer.value())
        }
        findViewById<Button>(R.id.button_start).setOnClickListener {
            val server = editServer.text.toString()
            if (server.isBlank())
                return@setOnClickListener
            if (mExitFlag) {
                if (senderTask.isActive || receiverTask.isActive)
                    return@setOnClickListener
                mExitFlag = false
                (it as Button).setText(R.string.stop)
                editServer.isEnabled = false
                settings.isEnabled = false

                app.lastServer.update(server)
                val address = parseAddress(server)
                if (!mTCPMode)
                    sendConnect(address)
                currentPacketId = 1
                senderTask.execute(lifecycleScope, address)
                receiverTask.execute(lifecycleScope, address)
                pingPongTask.execute(lifecycleScope)
            } else {
                sendDisconnect(parseAddress(server))
                mExitFlag = true
                (it as Button).setText(R.string.start)
                editServer.isEnabled = true
                settings.isEnabled = true
                senderTask.cancel()
                receiverTask.cancel()
                pingPongTask.cancel()
            }
        }

        findViewById<Button>(R.id.button_coin).setOnClickListener {
            if(!mExitFlag)
                sendFunctionKey(parseAddress(editServer.text.toString()), FunctionButton.FUNCTION_COIN)
        }
        findViewById<Button>(R.id.button_card).setOnClickListener {
            if(!mExitFlag)
                sendFunctionKey(parseAddress(editServer.text.toString()), FunctionButton.FUNCTION_CARD)
        }

        val checkSimpleAir = findViewById<CheckBox>(R.id.check_simple_air)
        mAirSource = app.airSource.value()
        findViewById<TextView>(R.id.text_switch_air).apply {
            setOnClickListener {
                mAirSource = when (mAirSource) {
                    0 -> {
                        text = getString(R.string.gyro_air)
                        mEnableAir = true
                        checkSimpleAir.isEnabled = true
                        1
                    }
                    1 -> {
                        text = getString(R.string.accel_air)
                        checkSimpleAir.isEnabled = false
                        2
                    }
                    2 -> {
                        text = getString(R.string.touch_air)
                        checkSimpleAir.isEnabled = true
                        3
                    }
                    else -> {
                        text = getString(R.string.disable_air)
                        checkSimpleAir.isEnabled = false
                        mEnableAir = false
                        0
                    }
                }
                app.airSource.update(mAirSource)
            }
            text = getString(when (mAirSource) {
                0 -> { checkSimpleAir.isEnabled = false; R.string.disable_air }
                1 -> { checkSimpleAir.isEnabled = true; R.string.gyro_air }
                2 -> { checkSimpleAir.isEnabled = false; R.string.accel_air }
                else -> { checkSimpleAir.isEnabled = true; R.string.touch_air }
            })
        }
        checkSimpleAir.apply {
            setOnCheckedChangeListener { _, isChecked ->
                mSimpleAir = isChecked
                app.simpleAir.update(isChecked)
            }
            isChecked = app.simpleAir.value()
        }
        mEnableAir = app.enableAir.value()
        mSimpleAir = app.simpleAir.value()

        findViewById<View>(R.id.button_test).setOnTouchListener { view, event ->
            mTestButton = when(event.actionMasked) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN -> true
                else -> false
            }
            //mInputQueue.add(InputEvent(serviceButton = mServiceButton, testButton = mTestButton))
            view.performClick()
        }
        findViewById<View>(R.id.button_service).setOnTouchListener { view, event ->
            mServiceButton = when(event.actionMasked) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN -> true
                else -> false
            }
            //mInputQueue.add(InputEvent(serviceButton = mServiceButton, testButton = mTestButton))
            view.performClick()
        }

        findViewById<CheckBox>(R.id.check_show_delay).apply {
            setOnCheckedChangeListener { _, isChecked ->
                mShowDelay = isChecked
                mDelayText.visibility = if (isChecked) View.VISIBLE else View.GONE
                app.showDelay.update(isChecked)
            }
            isChecked = app.showDelay.value()
        }

        mTCPMode = app.tcpMode.value()
        findViewById<TextView>(R.id.text_mode).apply {
            text = getString(if (mTCPMode) R.string.tcp else R.string.udp)
            setOnClickListener {
                if (!mExitFlag)
                    return@setOnClickListener
                text = getString(if (mTCPMode) {
                    mTCPMode = false
                    R.string.udp
                } else {
                    mTCPMode = true
                    R.string.tcp
                })
                app.tcpMode.update(mTCPMode)
            }
        }
        initTasks()
        //for (id in mButtonIds)
            //mButtons.add(findViewById(id))

        vibratorTask.execute(lifecycleScope)

        mSensorCallback = {
            val lowest = mGyroLowestBound
            val highest = mGyroHighestBound
            val steps = (highest - lowest) / numOfAirBlock
            val current = abs(it)
            mCurrentAirHeight = if (mSimpleAir) {
                when (current) {
                    in 0f..lowest -> 6
                    else -> 0
                }
            } else {
                when (current) {
                    in 0f..lowest -> 6
                    in lowest..highest -> {
                        ((highest - current) / steps).toInt()
                    }
                    else -> 0
                }
            }
        }

        val contentView = findViewById<ViewGroup>(android.R.id.content)
        contentView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                contentView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                /*
                val dm = DisplayMetrics()
                (applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
                windowWidth = dm.widthPixels.toFloat()
                windowHeight = dm.heightPixels.toFloat()*/
                /*
                val point = Point()
                (application.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(point)
                windowWidth = point.x.toFloat()
                windowHeight = point.y.toFloat()*/
                val arr = IntArray(2)
                contentView.getLocationOnScreen(arr)
                windowWidth = contentView.width.toFloat()
                windowHeight = contentView.height.toFloat()
                initTouchArea(arr[0], arr[1])
            }
        })
    }

    private fun initTouchArea(windowLeft: Int, windowTop: Int) {
        val expandControl = findViewById<ExpandableLayout>(R.id.expand_control)
        val textExpand = findViewById<TextView>(R.id.text_expand)
        textExpand.setOnClickListener {
            if (expandControl.isExpanded) {
                (it as TextView).setText(R.string.expand)
                expandControl.collapse()
            } else {
                (it as TextView).setText(R.string.collapse)
                expandControl.expand()
            }
        }

        val textInfo = findViewById<TextView>(R.id.text_info)
        findViewById<CheckBox>(R.id.check_debug).setOnCheckedChangeListener { _, isChecked ->
            mDebugInfo = isChecked
            textInfo.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        gapWidth = windowWidth / (numOfButtons * buttonWidthToGap + numOfGaps)
        buttonWidth = gapWidth * buttonWidthToGap
        //val buttonWidth = windowWidth / numOfButtons
        val buttonBlockWidth = buttonWidth + gapWidth
        val buttonAreaHeight = windowHeight * 0.5f
        val airAreaHeight = windowHeight * 0.35f
        val airBlockHeight = (buttonAreaHeight - airAreaHeight) / numOfAirBlock

        mLEDBitmap = Bitmap.createBitmap(windowWidth.toInt(), buttonAreaHeight.toInt(), Bitmap.Config.RGB_565)
        mLEDCanvas = Canvas(mLEDBitmap)
        mButtonRenderer = findViewById(R.id.button_render_area)
        mButtonRenderer.background = BitmapDrawable(resources, mLEDBitmap)

        findViewById<View>(R.id.touch_area).setOnTouchListener { view, event ->
            if (expandControl.isExpanded)
                textExpand.callOnClick()
            view ?: return@setOnTouchListener view.performClick()
            event ?: return@setOnTouchListener view.performClick()
            if (mTouchAreaRect == null) {
                val arr = IntArray(2)
                view.getLocationOnScreen(arr)
                mTouchAreaRect = Rect(arr[0], arr[1], arr[0] + view.width, arr[1] + view.height)
            }
            val currentAirAreaHeight = if (mAirSource != 3) 0f else airAreaHeight
            val currentButtonAreaHeight = if (mAirSource != 3) 0f else buttonAreaHeight
            val totalTouches = event.pointerCount
            val touchedButtons = HashSet<Int>()
            var thisAirHeight = 6
            var maxTouchedSize = 0f
            if (event.action != KeyEvent.ACTION_UP && event.action != MotionEvent.ACTION_CANCEL) {
                var ignoredIndex = -1
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP)
                    ignoredIndex = event.actionIndex
                for (i in 0 until totalTouches) {
                    if (i == ignoredIndex)
                        continue
                    val x = event.getX(i) + mTouchAreaRect!!.left - windowLeft
                    val y = event.getY(i) + mTouchAreaRect!!.top - windowTop
                    when(y) {
                        in 0f..currentAirAreaHeight -> {
                            thisAirHeight = 0
                        }
                        in currentAirAreaHeight..currentButtonAreaHeight -> {
                            val curAir = ((y - airAreaHeight) / airBlockHeight).toInt()
                            thisAirHeight = if(mSimpleAir) 0 else thisAirHeight.coerceAtMost(curAir)
                        }
                        in currentButtonAreaHeight..windowHeight -> {
                            val pointPos = x / buttonBlockWidth
                            var index = pointPos.toInt()
                            if (index > numOfButtons) index = numOfButtons

                            if (mEnableTouchSize) {
                                var centerButton = index * 2
                                if (touchedButtons.contains(centerButton)) centerButton++
                                var leftButton = ((index - 1) * 2).coerceAtLeast(0)
                                if (touchedButtons.contains(leftButton)) leftButton++
                                var rightButton = ((index + 1) * 2).coerceAtMost(numOfButtons * 2)
                                if (touchedButtons.contains(rightButton)) rightButton++
                                var left2Button = ((index - 2) * 2).coerceAtLeast(0)
                                if (touchedButtons.contains(left2Button)) left2Button++
                                var right2Button = ((index + 2) * 2).coerceAtMost(numOfButtons * 2)
                                if (touchedButtons.contains(right2Button)) right2Button++

                                val currentSize = event.getSize(i)
                                maxTouchedSize = maxTouchedSize.coerceAtLeast(currentSize)

                                touchedButtons.add(centerButton)
                                when ((pointPos - index) * 4) {
                                    in 0f..1f -> {
                                        touchedButtons.add(leftButton)
                                        if (currentSize >= mExtraFatTouchSizeThreshold) {
                                            touchedButtons.add(left2Button)
                                            touchedButtons.add(rightButton)
                                        }
                                    }
                                    in 1f..3f -> {
                                        if (currentSize >= mFatTouchSizeThreshold) {
                                            touchedButtons.add(leftButton)
                                            touchedButtons.add(rightButton)
                                        }
                                        if (currentSize >= mExtraFatTouchSizeThreshold) {
                                            touchedButtons.add(left2Button)
                                            touchedButtons.add(right2Button)
                                        }
                                    }
                                    in 3f..4f -> {
                                        touchedButtons.add(rightButton)
                                        if (currentSize >= mExtraFatTouchSizeThreshold) {
                                            touchedButtons.add(leftButton)
                                            touchedButtons.add(right2Button)
                                        }
                                    }
                                }
                            } else {
                                if (index > 15) index = 15
                                var targetIndex = index * 2
                                if (touchedButtons.contains(targetIndex)) targetIndex++
                                touchedButtons.add(targetIndex)
                                if (index > 0) {
                                    if ((pointPos - index) * 4 < 1) {
                                        targetIndex = (index - 1) * 2
                                        if (touchedButtons.contains(targetIndex)) targetIndex++
                                        touchedButtons.add(targetIndex)
                                    }
                                } else if (index < 31) {
                                    if ((pointPos - index) * 4 > 3) {
                                        targetIndex = (index + 1) * 2
                                        if (touchedButtons.contains(targetIndex)) targetIndex++
                                        touchedButtons.add(targetIndex)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else
                thisAirHeight = 6
            if (mEnableVibrate) {
                if (hasNewKeys(mLastButtons, touchedButtons))
                    mVibrationQueue.add(vibrateLength)
                else if (touchedButtons.isEmpty())
                    mVibrationQueue.clear()
            }
            mLastButtons = touchedButtons
            if (mAirSource == 3)
                mCurrentAirHeight = thisAirHeight
            //mInputQueue.add(InputEvent(touchedButtons, mCurrentAirHeight))
            if (mDebugInfo)
                textInfo.text = getString(R.string.debug_info, mCurrentAirHeight, touchedButtons.toString(), maxTouchedSize, event.toString())
            view.performClick()
        }
    }

    override fun onResume() {
        super.onResume()
        if (mSensorManager == null)
            mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyro = mSensorManager?.getDefaultSensor(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) Sensor.TYPE_GAME_ROTATION_VECTOR else Sensor.TYPE_ROTATION_VECTOR)
        mSensorManager?.registerListener(listener, gyro, 10000)
        val accel = mSensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        mSensorManager?.registerListener(listener, accel, 10000)
        enableNfcForegroundDispatch()
        loadPreference()
    }

    private fun loadPreference() {
        mEnableTouchSize = app.enableTouchSize.value()
        mFatTouchSizeThreshold = app.fatTouchThreshold.value()
        mExtraFatTouchSizeThreshold = app.extraFatTouchThreshold.value()
        mEnableNFC = app.enableNFC.value()
        mEnableVibrate = app.enableVibrate.value()
        mGyroLowestBound = app.gyroAirLowestBound.value()
        mGyroHighestBound = app.gyroAirHighestBound.value()
        mAccelThreshold = app.accelAirThreshold.value()
    }

    private fun enableNfcForegroundDispatch() {
        try {
            val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val nfcPendingIntent = PendingIntent.getActivity(this, 0, intent, 0)
            adapter?.enableForegroundDispatch(this, nfcPendingIntent, null, null)
        } catch (ex: IllegalStateException) {
            Log.e(TAG, "Error enabling NFC foreground dispatch", ex)
        }
    }

    private fun disableNfcForegroundDispatch() {
        try {
            adapter?.disableForegroundDispatch(this)
        } catch (ex: IllegalStateException) {
            Log.e(TAG, "Error disabling NFC foreground dispatch", ex)
        }
    }

    override fun onPause() {
        disableNfcForegroundDispatch()
        mSensorManager?.unregisterListener(listener)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus)
            setImmersive()
    }

    private var exitTime: Long = 0

    override fun onBackPressed() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - exitTime > 1500) {
            Toast.makeText(this, R.string.press_again_to_exit, Toast.LENGTH_SHORT).show()
            exitTime = currentTime
        } else {
            finish()
        }
    }

    private fun setImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    private fun hasNewKeys(oldKeys: MutableSet<Int>, newKeys: MutableSet<Int>): Boolean {
        for (i in newKeys)
            if (!oldKeys.contains(i)) return true
        return false
    }

    private fun parseAddress(address: String): InetSocketAddress? {
        val parts = address.split(":")
        return when(parts.size) {
            1 -> InetSocketAddress(parts[0], serverPort)
            2 -> InetSocketAddress(parts[0], parts[1].toInt())
            else -> null
        }
    }

    private fun Char.byte() = code.toByte()
    private fun initTasks() {
        receiverTask = AsyncTaskUtil.AsyncTask.make(
            doInBackground = {
                val address = it[0] ?: return@make
                if (mTCPMode) {
                    val buffer = ByteArray(256)
                    while (!mExitFlag) {
                        if (!this::mTCPSocket.isInitialized || !mTCPSocket.isConnected || mTCPSocket.isClosed) {
                            Thread.sleep(50)
                            continue
                        }
                        try {
                            val dataSize = mTCPSocket.getInputStream().read(buffer, 0, 256)
                            if (dataSize >= 3) {
                                if (dataSize >= 100 && buffer[1] == 'L'.byte() && buffer[2] == 'E'.byte() && buffer[3] == 'D'.byte()) {
                                    setLED(buffer)
                                }
                                if (dataSize >= 4 && buffer[1] == 'P'.byte() && buffer[2] == 'O'.byte() && buffer[3] == 'N'.byte()) {
                                    val delay = calculateDelay(buffer)
                                    if (delay > 0f)
                                        mCurrentDelay = delay
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                } else {
                    val socket = try {
                        DatagramSocket(serverPort).apply {
                            reuseAddress = true
                            soTimeout = 1000
                        }
                    } catch (e: BindException) {
                        e.printStackTrace()
                        return@make
                    }
                    val buffer = ByteArray(256)
                    val packet = DatagramPacket(buffer, buffer.size)
                    fun InetSocketAddress.toHostString(): String? {
                        if (hostName != null)
                            return hostName
                        if (this.address != null)
                            return this.address.hostName ?: this.address.hostAddress
                        return null
                    }
                    while (!mExitFlag) {
                        try {
                            socket.receive(packet)
                            if (packet.address.hostAddress == address.toHostString() && packet.port == address.port) {
                                val data = packet.data
                                if (data.size >= 3) {
                                    if (data.size >= 100 && data[1] == 'L'.byte() && data[2] == 'E'.byte() && data[3] == 'D'.byte()) {
                                        setLED(data)
                                    }
                                    if (data.size >= 4 && data[1] == 'P'.byte() && data[2] == 'O'.byte() && data[3] == 'N'.byte()) {
                                        val delay = calculateDelay(data)
                                        if (delay > 0f)
                                            mCurrentDelay = delay
                                    }
                                }
                            }
                        } catch (e: SocketTimeoutException) {
                            // ignore, try again
                        }
                    }
                    socket.close()
                }
            }
        )
        senderTask = AsyncTaskUtil.AsyncTask.make(
            doInBackground = {
                val address = it[0] ?: return@make
                if (mTCPMode) {
                    try {
                        mTCPSocket = Socket().apply {
                            tcpNoDelay = true
                        }
                        mTCPSocket.connect(address)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        return@make
                    }
                    while (!mExitFlag) {
                        if (mShowDelay)
                            sendTCPPing()
                        val buttons = InputEvent(mLastButtons, mCurrentAirHeight, mTestButton, mServiceButton)
                        val buffer = applyKeys(buttons, IoBuffer())
                        try {
                            mTCPSocket.getOutputStream().write(constructBuffer(buffer))
                            if (mEnableNFC)
                                mTCPSocket.getOutputStream().write(constructCardData())
                        } catch (e: Exception) {
                            e.printStackTrace()
                            continue
                        }
                        //Thread.yield()
                        Thread.sleep(1)
                    }
                } else {
                    val socket = try {
                        DatagramSocket().apply {
                            reuseAddress = true
                            soTimeout = 1000
                        }
                    } catch (e: BindException) {
                        e.printStackTrace()
                        return@make
                    }
                    try {
                        socket.connect(address)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        return@make
                    }
                    while (!mExitFlag) {
                        if (mShowDelay)
                            sendPing(address)
                        val buttons = InputEvent(mLastButtons, mCurrentAirHeight, mTestButton, mServiceButton)
                        val buffer = applyKeys(buttons/* ?: InputEvent()*/, IoBuffer())
                        val packet = constructPacket(buffer)
                        try {
                            socket.send(packet)
                            if (mEnableNFC)
                                socket.send(constructCardPacket())
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Thread.sleep(100)
                            continue
                        }
                        //Thread.sleep(2)
                        Thread.sleep(1)
                    }
                    socket.close()
                }
            }
        )
        pingPongTask = AsyncTaskUtil.AsyncTask.make(
            doInBackground = {
                while (!mExitFlag) {
                    if (!mShowDelay) {
                        Thread.sleep(250)
                        continue
                    }
                    if (mCurrentDelay >= 0f) {
                        runOnUiThread { mDelayText.text = getString(R.string.current_latency, mCurrentDelay) }
                    }
                    Thread.sleep(200)
                }
            }
        )
        vibratorTask = AsyncTaskUtil.AsyncTask.make(
            doInBackground = {
                while (true) {
                    if (!mEnableVibrate) {
                        Thread.sleep(250)
                        continue
                    }
                    val next = mVibrationQueue.poll()
                    if (next != null)
                        vibrateMethod(next)
                    Thread.sleep(10)
                }
            }
        )
    }

    @Suppress("unused")
    enum class FunctionButton {
        UNDEFINED, FUNCTION_COIN, FUNCTION_CARD
    }

    class IoBuffer {
        var length: Int = 0
        var header = ByteArray(3)
        var air = ByteArray(6)
        var slider = ByteArray(32)
        var testBtn = false
        var serviceBtn = false
    }

    private fun getLocalIPAddress(useIPv4: Boolean = true): ByteArray {
        try {
            val interfaces: List<NetworkInterface> = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs: List<InetAddress> = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.address
                        if (useIPv4) {
                            if (addr is Inet4Address) return sAddr
                        } else {
                            if (addr is Inet6Address) return sAddr
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
        return byteArrayOf()
    }

    private fun sendConnect(address: InetSocketAddress?) {
        address ?: return
        thread {
            val selfAddress = getLocalIPAddress()
            if (selfAddress.isEmpty()) return@thread
            val buffer = ByteArray(21)
            byteArrayOf('C'.byte(), 'O'.byte(), 'N'.byte()).copyInto(buffer, 1)
            ByteBuffer.wrap(buffer)
                    .put(4, if (selfAddress.size == 4) 1.toByte() else 2.toByte())
                    .putShort(5, serverPort.toShort())
            selfAddress.copyInto(buffer, 7)
            buffer[0] = (3 + 1 + 2 + selfAddress.size).toByte()
            try {
                val socket = DatagramSocket()
                val packet = DatagramPacket(buffer, buffer.size)
                socket.apply {
                    connect(address)
                    send(packet)
                    close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun sendDisconnect(address: InetSocketAddress?) {
        address ?: return
        thread {
            val buffer = byteArrayOf(3, 'D'.byte(), 'I'.byte(), 'S'.byte())
            if (mTCPMode) {
                try {
                    mTCPSocket.getOutputStream().write(buffer)
                    mTCPSocket.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                try {
                    val socket = DatagramSocket()
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.apply {
                        connect(address)
                        send(packet)
                        close()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun sendFunctionKey(address: InetSocketAddress?, function: FunctionButton) {
        address ?: return
        thread {
            val buffer = byteArrayOf(4, 'F'.byte(), 'N'.byte(), 'C'.byte(), function.ordinal.toByte())
            if (mTCPMode) {
                try {
                    mTCPSocket.getOutputStream().write(buffer)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                try {
                    val socket = DatagramSocket()
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.apply {
                        connect(address)
                        send(packet)
                        close()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private val pingInterval = 100L
    private var lastPingTime = 0L
    private fun sendPing(address: InetSocketAddress?) {
        address ?: return
        if (System.currentTimeMillis() - lastPingTime < pingInterval) return
        lastPingTime = System.currentTimeMillis()
        val buffer = ByteArray(12)
        byteArrayOf(11, 'P'.byte(), 'I'.byte(), 'N'.byte()).copyInto(buffer)
        ByteBuffer.wrap(buffer, 4, 8).putLong(SystemClock.elapsedRealtimeNanos())
        try {
            val socket = DatagramSocket()
            val packet = DatagramPacket(buffer, buffer.size)
            socket.apply {
                connect(address)
                send(packet)
                close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendTCPPing() {
        if (System.currentTimeMillis() - lastPingTime < pingInterval) return
        lastPingTime = System.currentTimeMillis()
        val buffer = ByteArray(12)
        byteArrayOf(11, 'P'.byte(), 'I'.byte(), 'N'.byte()).copyInto(buffer)
        ByteBuffer.wrap(buffer, 4, 8).putLong(SystemClock.elapsedRealtimeNanos())
        try {
            mTCPSocket.getOutputStream().write(buffer)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun calculateDelay(data: ByteArray): Float {
        val currentTime = SystemClock.elapsedRealtimeNanos()
        val lastPingTime = ByteBuffer.wrap(data).getLong(4)
        return (currentTime - lastPingTime) / 2000000.0f
    }

    private var currentPacketId = 1
    private fun constructBuffer(buffer: IoBuffer): ByteArray {
        val realBuf = ByteArray(48)
        realBuf[0] = buffer.length.toByte()
        buffer.header.copyInto(realBuf, 1)
        ByteBuffer.wrap(realBuf).putInt(4, currentPacketId++)
        if (mEnableAir) {
            buffer.air.copyInto(realBuf, 8)
            buffer.slider.copyInto(realBuf, 14)
            realBuf[46] = if (buffer.testBtn) 0x01 else 0x00
            realBuf[47] = if (buffer.serviceBtn) 0x01 else 0x00
        } else {
            buffer.slider.copyInto(realBuf, 8)
            realBuf[40] = if (buffer.testBtn) 0x01 else 0x00
            realBuf[41] = if (buffer.serviceBtn) 0x01 else 0x00
        }
        return realBuf
    }

    private fun constructPacket(buffer: IoBuffer): DatagramPacket {
        val realBuf = constructBuffer(buffer)
        return DatagramPacket(realBuf, buffer.length + 1)
    }

    private fun constructCardData(): ByteArray {
        val buf = ByteArray(24)
        byteArrayOf(15, 'C'.byte(), 'R'.byte(), 'D'.byte()).copyInto(buf)
        buf[4] = if (hasCard) 1 else 0
        buf[5] = cardType.ordinal.toByte()
        if (hasCard)
            cardId.copyInto(buf, 6)
        return buf
    }

    private fun constructCardPacket(): DatagramPacket {
        val buf = constructCardData()
        return DatagramPacket(buf, buf[0] + 1)
    }

    private val airUpdateInterval = 10L
    private var mLastAirHeight = 6
    private var mLastAirUpdateTime = 0L
    private fun applyKeys(event: InputEvent, buffer: IoBuffer): IoBuffer {
        return buffer.apply {
            if (mEnableAir) {
                buffer.length = 47
                buffer.header = byteArrayOf('I'.byte(), 'N'.byte(), 'P'.byte())
            } else {
                buffer.length = 41
                buffer.header = byteArrayOf('I'.byte(), 'P'.byte(), 'T'.byte())
            }

            if (event.keys != null && event.keys.isNotEmpty()) {
                for (i in 0 until 32) {
                    buffer.slider[31 - i] = if (event.keys.contains(i)) 0x80.toByte() else 0x0
                }
            }

            if (mEnableAir) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - mLastAirUpdateTime > airUpdateInterval) {
                    mLastAirHeight += if (mLastAirHeight < mCurrentAirHeight) 1 else if (mLastAirHeight > mCurrentAirHeight) -1 else 0
                    mLastAirUpdateTime = currentTime
                }
                if (mLastAirHeight != 6) {
                    for (i in mLastAirHeight..5) {
                        buffer.air[mAirIdx[i]] = 1
                    }
                }
            }

            buffer.serviceBtn = event.serviceButton
            buffer.testBtn = event.testButton
        }
    }

    private fun setLED(status: ByteArray) {
        val blockCount = numOfButtons + numOfGaps
        val steps = 32 / blockCount
        val offset = 4

        var drawXOffset = 0f
        val drawHeight = mLEDBitmap.height

        for (i in (blockCount - 1).downTo(0)) {
            val index = offset + (i * steps * 3)
            val blue = status[index].toInt() and 0xff
            val red = status[index + 1].toInt() and 0xff
            val green = status[index + 2].toInt() and 0xff
            val color = 0xff000000 or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()

            val left = drawXOffset
            val width = when(i.rem(2)) {
                0 -> buttonWidth
                1 -> gapWidth
                else -> continue
            }
            val right = left + width
            mLEDCanvas.drawRect(left, 0f, right, drawHeight.toFloat(), color.toPaint())
            drawXOffset += width
        }
        mButtonRenderer.postInvalidate()
    }
    private fun Long.toPaint(): Paint = Paint().apply { color = toInt() }

    companion object {
        private const val TAG = "Brokenithm"
    }
}