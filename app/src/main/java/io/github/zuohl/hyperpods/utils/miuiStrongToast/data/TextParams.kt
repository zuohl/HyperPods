package io.github.zuohl.hyperpods.utils.miuiStrongToast.data

import kotlinx.serialization.Serializable

@Serializable
data class TextParams(
    var text: String? = null,
    var textColor: Int = 0
)
