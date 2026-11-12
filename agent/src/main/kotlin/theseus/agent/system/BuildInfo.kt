package theseus.agent.system

import java.util.Properties

/** Версия читается из ресурса собранного дистрибутива, а не из текущей рабочей копии или env. */
object BuildInfo {
    val version: String =
        Properties()
            .apply {
                requireNotNull(BuildInfo::class.java.classLoader.getResourceAsStream("build-info.properties")).use(::load)
            }.getProperty("version")
            .also { require(!it.isNullOrBlank()) }
}
