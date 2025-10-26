package com.panomc.plugins.pano.core.platform

import com.panomc.plugins.pano.core.util.TextUtil.convertToSnakeCase
import java.lang.reflect.ParameterizedType

abstract class PlatformMessageHandler<R : PlatformMessage> {
    @Suppress("UNCHECKED_CAST")
    val messageClass: Class<R> by lazy {
        val superclass = (this::class.java.genericSuperclass as ParameterizedType)
        superclass.actualTypeArguments[0] as Class<R>
    }

    abstract suspend fun handle(request: R)

    fun getHandlerName() = this.javaClass.simpleName.replace("Handler", "").convertToSnakeCase().uppercase()

}