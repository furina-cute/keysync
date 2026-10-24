package com.devoid.keysync.model

import kotlinx.serialization.Serializable

@Serializable
data class FullConfig(
    val appConfig: AppConfig,
    val addedPackages: List<String>,
    val buttonsConfigs: Map<String, List<DraggableItem>>
)
