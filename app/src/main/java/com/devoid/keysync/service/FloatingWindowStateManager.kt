package com.devoid.keysync.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.core.view.WindowInsetsCompat
import androidx.datastore.preferences.core.Preferences
import com.devoid.keysync.data.local.DataStoreManager
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.DEFAULT_UI_TOGGLE_KEYCODE
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.DraggableItemType
import com.devoid.keysync.domain.EventHandler
import com.devoid.keysync.data.external.ShizukuSystemServerAPi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

class FloatingWindowStateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStoreManager: DataStoreManager
) {

    val scope = CoroutineScope(Dispatchers.Main +SupervisorJob())

    private val shizukuSystemServerAPi = ShizukuSystemServerAPi()
    private val eventHandler: EventHandler by lazy { shizukuSystemServerAPi.getEventHandler() }
    val windowManager: WindowManager by lazy { context.getSystemService(WindowManager::class.java) }
    private val displayMetrics = context.resources.displayMetrics

    /** Pointer speed while shooting mode is disabled (normal mouse control). */
    val pointerSensitivity = MutableStateFlow(0.5f)

    /** Pointer speed while shooting mode is enabled (in game camera control). */
    val shootingSensitivity = MutableStateFlow(0.5f)

    val overlayOpacity = MutableStateFlow(0.5f)

    /** Where the cursor re-appears after leaving shooting mode. */
    val pointerExitPosition = MutableStateFlow(EXIT_POSITION_LAST)

    companion object {
        const val EXIT_POSITION_LAST = "last"
        const val EXIT_POSITION_CENTER = "center"
    }

    private var buttonConfigKey :Preferences.Key<String>? = null

    private val _appConfig =MutableStateFlow(AppConfig.Default)
    val keysConfig = _appConfig.asStateFlow()

    /** Multiplier applied to raw mouse deltas out of game. */
    val sensitivity: Float
        get() = 10f * pointerSensitivity.value // at max 10x the original offset

    /** Multiplier applied to raw mouse deltas in game (shooting mode). */
    val shootingSensitivityFactor: Float
        get() = 10f * shootingSensitivity.value

    private val _isBubbleExpanded = MutableStateFlow(false)
    val isBubbleExpanded = _isBubbleExpanded.asStateFlow()

    private val _mousePointerOffset =
        MutableStateFlow(Offset(displayMetrics.widthPixels / 2f, displayMetrics.heightPixels / 2f))
    val pointerOffset = _mousePointerOffset.asStateFlow()

    private val _containerItems = MutableStateFlow(listOf<DraggableItem>())
    val containerItems = _containerItems.asStateFlow()

    val isShootingMode = eventHandler.shootingModeFlow

    init {
        scope.launch {
            isBubbleExpanded.drop(1).collect{isExpanded->
                if (isExpanded)
                    return@collect
                buttonConfigKey?.let {
                    dataStoreManager.save(DataStoreManager.OVERLAY_OPACITY,overlayOpacity.value)
                    dataStoreManager.save(DataStoreManager.POINTER_SENSITIVITY,pointerSensitivity.value)
                    dataStoreManager.save(DataStoreManager.SHOOTING_SENSITIVITY,shootingSensitivity.value)
                    dataStoreManager.save(DataStoreManager.POINTER_EXIT_POSITION,pointerExitPosition.value)
                    dataStoreManager.save(it,containerItems.value)
                }
                _appConfig.value = dataStoreManager.getKeyConfig(DataStoreManager.KEYS_CONFIG).first()
            }
        }
        scope.launch {
            var wasShooting = isShootingMode.value
            isShootingMode.collect { shooting ->
                if (wasShooting && !shooting) {
                    restorePointerAfterShootingMode()
                }
                wasShooting = shooting
            }
        }
        scope.launch {
            _appConfig.value = dataStoreManager.getKeyConfig(DataStoreManager.KEYS_CONFIG).first()
            eventHandler.setCancelableTouchMode(_appConfig.value.cancellableTouchMode)
            eventHandler.setNormalBtnTouchMode(_appConfig.value.normalBtnTouchMode)
            eventHandler.appConfig = _appConfig.value
        }

    }

    fun loadButtonsConfig(packageName:String){
        buttonConfigKey= DataStoreManager.getButtonsConfigKey(packageName)
        scope.launch {
            _containerItems.value = dataStoreManager.getButtons(buttonConfigKey!!).first()
                .map { item ->
                    // Older builds stored both tap positions of the UI toggle on top of
                    // each other, which made the second position impossible to find.
                    if (item is DraggableItem.CancelableKey && item.cancelPosition == item.position) {
                        item.cancelPosition = item.position + Offset(0f, 120f)
                    }
                    item
                }
            eventHandler.updateKeyMapping(containerItems.value)
            dataStoreManager.getFloat(DataStoreManager.OVERLAY_OPACITY).first()?.let {
                overlayOpacity.value = it
            }
            val savedNormalSensitivity =
                dataStoreManager.getFloat(DataStoreManager.POINTER_SENSITIVITY).first()
            val savedShootingSensitivity =
                dataStoreManager.getFloat(DataStoreManager.SHOOTING_SENSITIVITY).first()
            savedNormalSensitivity?.let { pointerSensitivity.value = it }
            // Older versions only had one sensitivity value: reuse it for shooting mode.
            shootingSensitivity.value = savedShootingSensitivity ?: pointerSensitivity.value
            dataStoreManager.getString(DataStoreManager.POINTER_EXIT_POSITION).first()?.let {
                pointerExitPosition.value = it
            }
        }
    }

    fun addNewItem(itemType: DraggableItemType) {
        val itemID = containerItems.value.sumOf { it.id } + 1
        val offset = Offset(0f,200f)
        val item = when (itemType) {
            DraggableItemType.KEY -> {
                DraggableItem.VariableKey(itemID, position = offset, size = 0)
            }

            DraggableItemType.WASD_KEY -> {
                DraggableItem.WASDGroup(itemID, offset)
            }

            DraggableItemType.SHOOTING_MODE -> {
                DraggableItem.FixedKey(
                    itemID,
                    offset,
                    size = 0,
                    type = itemType,
                    keyCode = keysConfig.value.shootingModeKeyCode
                )

            }

            DraggableItemType.FIRE -> {
                DraggableItem.FixedKey(
                    itemID,
                    offset,
                    size = 0,
                    type = itemType,
                    keyCode = keysConfig.value.fireKeyCode
                )
            }
            DraggableItemType.BAG_MAP->{
                DraggableItem.CancelableKey(
                    itemID,
                    offset,
                    // The second position must not overlap the first one, otherwise the
                    // user cannot tell where the "close" tap goes.
                    cancelPosition = offset + Offset(0f, 120f),
                    size = 0,
                    type = itemType,
                    // Without a key code the item was silently skipped when the key map
                    // was built, so the whole "UI toggle" did nothing.
                    keyCode = DEFAULT_UI_TOGGLE_KEYCODE
                )}
            DraggableItemType.SCOPE->{
                DraggableItem.FixedKey(
                    itemID,
                    offset,
                    size = 0,
                    type = itemType,
                    keyCode = keysConfig.value.scopeKeyCode
                    )
            }
        }
        _containerItems.value = containerItems.value.plus(item)
    }

    fun removeItem(id: Int) {
        _containerItems.value = _containerItems.value.filterNot { it.id == id }
    }

    fun onFloatingBubbleClick() {
        _isBubbleExpanded.value = !_isBubbleExpanded.value
        if (!_isBubbleExpanded.value) {
            eventHandler.updateKeyMapping(containerItems.value)
        }
    }

    fun onKeyEvent(keyEvent: KeyEvent): Boolean {
        if (_isBubbleExpanded.value)
            return false
        return eventHandler.handleKeyEvent(keyEvent)
    }

    fun onMouseEvent(motionEvent: MotionEvent): Boolean {
        if (isBubbleExpanded.value)
            return false
        when (motionEvent.action) {

            MotionEvent.ACTION_BUTTON_PRESS -> {
                eventHandler.mousePointerPosition = pointerOffset.value
                eventHandler.handleMouseButton(motionEvent.actionButton, true)
            }

            MotionEvent.ACTION_BUTTON_RELEASE -> {
                eventHandler.handleMouseButton(motionEvent.actionButton, false)
            }

            MotionEvent.ACTION_MOVE -> {
                val factor = if (isShootingMode.value)
                    shootingSensitivityFactor else sensitivity
                val offset = Offset(
                    motionEvent.rawX,
                    motionEvent.rawY
                ) * factor
                if (!isShootingMode.value) {
                    val position = _mousePointerOffset.value + offset
                    _mousePointerOffset.value = Offset(
                        position.x.coerceIn(0f, displayMetrics.widthPixels.toFloat()),
                        position.y.coerceIn(0f, displayMetrics.heightPixels.toFloat())
                    )
                }
                return eventHandler.handlePointerMove(offset)

            }
        }
        return true
    }

    fun clearActivePointers() {
        eventHandler.clear()
    }

    /** Rebuilds the key map, e.g. after the user assigned a new key in the overlay. */
    fun refreshKeyMapping() {
        eventHandler.updateKeyMapping(containerItems.value)
    }

    /**
     * Moves the cursor to the configured position once shooting mode is left.
     * "last" keeps the position the cursor had before entering shooting mode, because
     * mouse movement is swallowed while shooting mode is active.
     */
    private fun restorePointerAfterShootingMode() {
        if (pointerExitPosition.value == EXIT_POSITION_CENTER) {
            _mousePointerOffset.value = Offset(
                displayMetrics.widthPixels / 2f,
                displayMetrics.heightPixels / 2f
            )
        }
    }

    fun onDestroy(){
        _isBubbleExpanded.value = false
        eventHandler.clear()
        scope.cancel() // Cancel all coroutines
    }

    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    private fun getStatusBrHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.currentWindowMetrics.windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        } else {
            val resourceId =
                Resources.getSystem().getIdentifier("status_bar_height", "dimen", "android")
            return if (resourceId > 0) Resources.getSystem()
                .getDimensionPixelSize(resourceId) else 0

        }
    }
}