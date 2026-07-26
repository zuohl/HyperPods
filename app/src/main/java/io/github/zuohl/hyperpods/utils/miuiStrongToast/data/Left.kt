package io.github.zuohl.hyperpods.utils.miuiStrongToast.data

import kotlinx.serialization.Serializable

@Serializable
data class Left(
    var iconParams: IconParams? = null,
    var textParams: TextParams? = null
)
