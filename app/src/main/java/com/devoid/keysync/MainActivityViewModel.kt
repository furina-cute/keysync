package com.devoid.keysync

import android.content.Context
import android.content.Context.INPUT_SERVICE
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devoid.keysync.data.local.DataStoreManager
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.DraggableItemType
import com.devoid.keysync.model.FullConfig
import com.devoid.keysync.util.LocaleManager
import kotlinx.serialization.json.Json
import com.devoid.keysync.service.FloatingBubbleService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import javax.inject.Inject


@HiltViewModel
class MainActivityViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStoreManager: DataStoreManager
) :
    ViewModel(), Shizuku.OnRequestPermissionResultListener {
    private val TAG = "MainActivityViewModel"
    private val SHIZUKU_REQ_CODE = 10
    private val _shizukuState = MutableStateFlow<UiState>(UiState.ShizukuNotRunning)
    val shizukuState = _shizukuState.asStateFlow()

    private val _addedPackages = MutableStateFlow<List<String>>(listOf())
    val addedPackages = _addedPackages.asStateFlow()

    private val _appConfig = MutableStateFlow(AppConfig.Default)
    val appConfig = _appConfig.asStateFlow()


    private val _connectedDevices = MutableStateFlow<Map<Int, String>>(hashMapOf())
    val connectedDevices = _connectedDevices.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        val binder = Shizuku.getBinder()
        binder?.let {
            _shizukuState.value = UiState.ShizukuRunning(it)
        }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _shizukuState.value = UiState.ShizukuNotRunning
    }

    init {
        Shizuku.addRequestPermissionResultListener(this)
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        getShizukuBinder()
        getConnectedExternalDevices()
        viewModelScope.launch {
            _addedPackages.value = dataStoreManager.getList(DataStoreManager.ADDED_PACKAGES).first()
            val config = dataStoreManager.getKeyConfig(DataStoreManager.KEYS_CONFIG).first()
            // The locale preference is read very early (Application/Activity
            // attachBaseContext), keep it in sync with the persisted config.
            if (LocaleManager.getSavedLanguage(context) != config.locale) {
                LocaleManager.saveLanguage(context, config.locale)
            }
            _appConfig.value = config
        }
    }

    fun saveKeyConfig(newAppConfig: AppConfig) {
        // Snapshot the previous config: it is updated synchronously below, so the
        // remapping coroutine must not read the (already changed) live value.
        val oldConfig = appConfig.value
        val newFireKeyCode = newAppConfig.fireKeyCode
        val newShootingModeKeyCode = newAppConfig.shootingModeKeyCode
        val newLocale = newAppConfig.locale

        val localeChanged = newLocale != oldConfig.locale

        if (newFireKeyCode != oldConfig.fireKeyCode ||
            newShootingModeKeyCode != oldConfig.shootingModeKeyCode
        ) {
            viewModelScope.launch {
                dataStoreManager.getButtonsConfigKeys().first().forEach {
                    val buttonsConfig = dataStoreManager.getButtons(it).first().toMutableList()
                    buttonsConfig.forEachIndexed { index, value ->
                        if (value is DraggableItem.FixedKey) {
                            if (value.keyCode == oldConfig.fireKeyCode) {
                                buttonsConfig[index] = value.copy(keyCode = newFireKeyCode)
                            } else if (value.keyCode == oldConfig.shootingModeKeyCode) {
                                buttonsConfig[index] = value.copy(keyCode = newShootingModeKeyCode)
                            }
                        }
                    }
                    dataStoreManager.save(it, buttonsConfig)
                }
            }
        }
        if (localeChanged) {
            // Persist only. The Activity recreates itself afterwards and installs the
            // language in attachBaseContext - mutating a Context's configuration here
            // would not affect the already-created activity resources.
            LocaleManager.saveLanguage(context, newLocale)
        }

        // Update the in-memory state synchronously so a following recreate() renders
        // the new configuration right away.
        _appConfig.value = newAppConfig

        viewModelScope.launch {
            dataStoreManager.save(DataStoreManager.KEYS_CONFIG, newAppConfig)
        }
    }

    suspend fun exportConfig(): String {
        val addedPackages = _addedPackages.value
        val buttonsConfigs = mutableMapOf<String, String>()
        
        // Get all button configs
        dataStoreManager.getButtonsConfigKeys().first().forEach { key ->
            val buttons = dataStoreManager.getButtons(key).first()
            buttonsConfigs[key.name] = Json.encodeToString(buttons)
        }
        
        val fullConfig = FullConfig(
            appConfig = appConfig.value,
            addedPackages = addedPackages,
            buttonsConfigs = buttonsConfigs
        )
        
        return Json.encodeToString(FullConfig.serializer(), fullConfig)
    }
    
    suspend fun importConfig(json: String): Boolean {
        return try {
            val fullConfig = Json.decodeFromString(FullConfig.serializer(), json)
            
            // Save app config
            saveKeyConfig(fullConfig.appConfig)
            
            // Clear existing packages
            dataStoreManager.save(DataStoreManager.ADDED_PACKAGES, emptyList())
            
            // Add packages and their configs
            fullConfig.addedPackages.forEach { packageName ->
                dataStoreManager.saveList(DataStoreManager.ADDED_PACKAGES, fullConfig.addedPackages)
                
                val key = DataStoreManager.getButtonsConfigKey(packageName)
                val buttonsJson = fullConfig.buttonsConfigs[key.name]
                if (buttonsJson != null) {
                    val buttons = Json.decodeFromString<List<DraggableItem>>(buttonsJson)
                    dataStoreManager.save(key, buttons)
                }
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e.message}")
            false
        }
    }

    fun removePackages(packages: Collection<Int>) {
        viewModelScope.launch {
            _addedPackages.value = _addedPackages.value.filterIndexed { index, packageName ->
                if (index !in packages) {
                    true
                } else {
                    if (appConfig.value.deleteDataOnRemove) {
                        dataStoreManager.remove(DataStoreManager.getButtonsConfigKey(packageName))
                    }
                    false
                }
            }
            dataStoreManager.saveList(DataStoreManager.ADDED_PACKAGES, _addedPackages.value)
        }
    }

    fun addPackage(packages: String) {
        if (_addedPackages.value.contains(packages))
            return
        _addedPackages.value = _addedPackages.value.plus(packages)
        viewModelScope.launch {
            dataStoreManager.saveList(DataStoreManager.ADDED_PACKAGES, _addedPackages.value)
        }
    }

    fun getInstalledPackages(): List<PackageInfo> {
        return context.packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
            .filter { it.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
    }

    fun getPackageLabel(packageName: String): String {
        try {
            val packageInfo = context.packageManager.getApplicationInfo(packageName, 0)
            return context.packageManager.getApplicationLabel(packageInfo).toString()
        }catch (e:Exception){
            return "Not found"
        }
    }

    private fun getConnectedExternalDevices() {
        val inputManager = context.getSystemService(INPUT_SERVICE) as InputManager
        val deviceIds = inputManager.inputDeviceIds
        val devices = HashMap<Int, String>()
        deviceIds.forEach { deviceId ->
            val device = inputManager.getInputDevice(deviceId)
            device?.let {
                if (it.isExternal) {
                    devices[it.id] = getDeviceName(it)
                }
            }
        }
        _connectedDevices.value = devices
        inputManager.registerInputDeviceListener(object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                val device = inputManager.getInputDevice(deviceId)
                device?.let {
                    if (it.isExternal) {
                        _connectedDevices.value =
                            _connectedDevices.value.plus(it.id to getDeviceName(it))
                    }
                }
            }

            override fun onInputDeviceRemoved(deviceId: Int) {
                _connectedDevices.value = _connectedDevices.value.minus(deviceId)

            }

            override fun onInputDeviceChanged(deviceId: Int) {
                val device = inputManager.getInputDevice(deviceId)
                device?.let {
                    if (it.isExternal) {
                        _connectedDevices.value =
                            _connectedDevices.value.plus(it.id to getDeviceName(it))
                    }
                }
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun getDeviceName(inputDevice: InputDevice): String {
        return inputDevice.name.takeIf { name -> name.isNotEmpty() } ?: when (inputDevice.sources) {
            InputDevice.SOURCE_MOUSE -> "Mouse"
            InputDevice.SOURCE_KEYBOARD -> "Keyboard"
            else -> "Unknown Device"
        }

    }

    suspend fun getPackageIcon(packageName: String): Drawable {
        return withContext(Dispatchers.IO) {
            try {
                return@withContext   context.packageManager.getApplicationIcon(packageName)
            }catch (e:Exception){
                return@withContext context.getDrawable(R.drawable.apk_document)!!
            }
        }
    }

    private fun getShizukuBinder() {
        Shizuku.getBinder()?.let {
            _shizukuState.value = UiState.ShizukuRunning(it)
        } ?: {
            _shizukuState.value = UiState.ShizukuNotRunning
        }
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        if (granted) {
            Shizuku.getBinder()?.let {
                _shizukuState.value = UiState.ShizukuRunning(it)
            } ?: { _shizukuState.value = UiState.ShizukuNotRunning }
        }
    }

    fun requestShizukuPermission(): Boolean {
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            return false
        }
        // Request the permission
        Shizuku.requestPermission(SHIZUKU_REQ_CODE)
        return true
    }

    fun openGithub() {
        val url = "https://github.com/aka-munan/keysync"
        val i = Intent(Intent.ACTION_VIEW)
        i.setData(Uri.parse(url))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }

    sealed interface UiState {
        data object ShizukuNotRunning : UiState
        data class ShizukuRunning(val binder: IBinder) : UiState
    }

    override fun onCleared() {
        Shizuku.removeRequestPermissionResultListener(this)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Log.i("MainActivityViewModel", "onCleared: main viewModelCleared ")
    }

    fun launchPackage(packageName: String,packageManager: PackageManager,isServiceRunning:Boolean) {
        try {
            val packageIntent =
                packageManager.getLaunchIntentForPackage(packageName)
            packageIntent?.let {
                it.flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            context.startActivity(packageIntent)
            if (!isServiceRunning) {
                launchFloatingBubbleService(packageName)
            }
        }catch (e:Exception){
            //failed to launch package
            Log.e(TAG,"failed to launch package",e)
        }
    }
    private fun launchFloatingBubbleService(packageName: String) {
        val serviceIntent = Intent(
            context,
            FloatingBubbleService::class.java
        )
        serviceIntent.putExtra(FloatingBubbleService.INTENT_EXTRA_PACAKAGE, packageName)
        context.startForegroundService(serviceIntent)
    }
}