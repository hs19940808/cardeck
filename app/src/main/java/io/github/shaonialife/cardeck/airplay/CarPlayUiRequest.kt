package io.github.shaonialife.cardeck.airplay

/** requestUI 只映射到已有车机页面，不把车机 URL 作为任意 Android Intent 执行。 */
internal enum class CarPlayUiRequest {
    HOME,
    MAPS,
    MUSIC,
    ;

    companion object {
        /** 无 URL 表示打开 CarPlay；未知 scheme 保留给日志诊断，不抢占当前页面。 */
        fun from(command: Map<*, *>): CarPlayUiRequest? {
            if (!command.containsKey("params")) return HOME
            val params =
                command["params"] as? Map<*, *>
                    ?: return null
            if (!params.containsKey("url")) return HOME
            val url = params["url"] as? String ?: return null
            return when (url.trim().lowercase()) {
                "" -> HOME
                "maps:" -> MAPS
                "music:" -> MUSIC
                else -> null
            }
        }
    }
}
