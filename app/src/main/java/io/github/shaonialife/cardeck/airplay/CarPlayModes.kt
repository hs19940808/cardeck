package io.github.shaonialife.cardeck.airplay

/** 保存单次会话的资源归属，避免车机退回原车界面时被固定的资源声明抢回屏幕。 */
internal class CarPlayModes {
    private var resources = mapOf(1L to Resource(), 2L to Resource())
    private var appStates =
        mapOf(
            2L to mapOf("appStateID" to 2L, "entity" to 0L),
            3L to mapOf("appStateID" to 3L, "entity" to 0L),
            1L to mapOf("appStateID" to 1L, "entity" to 0L, "speechMode" to -1L),
        )

    /** 返回独立快照，供 changeModes 应答与 modesChanged 使用同一份资源状态。 */
    @Synchronized
    fun snapshot(): Map<String, Any> =
        linkedMapOf(
            "appStates" to appStates.values.toList(),
            "resources" to resources.map { (id, state) -> state.snapshot(id) },
        )

    /** 用户主动请求 CarPlay 页面时仅取回主屏，保留原车音频和通话状态。 */
    @Synchronized
    fun takeScreen() {
        resources = resources + (1L to Resource())
    }

    /** 整批校验后更新；拒绝畸形请求时不留下已处理一半的资源状态。 */
    @Synchronized
    fun change(params: Map<*, *>?): Map<String, Any> {
        if (params == null) return mapOf("status" to 1L)
        val resourceChanges = entries(params, "resources") ?: return mapOf("status" to 1L)
        val stateChanges = entries(params, "appStates") ?: return mapOf("status" to 1L)
        val nextResources = resources.toMutableMap()
        val nextAppStates = appStates.toMutableMap()
        for (entry in resourceChanges) {
            val id = (entry["resourceID"] as? Number)?.toLong() ?: return mapOf("status" to 1L)
            val resource = nextResources[id] ?: return mapOf("status" to 1L)
            val transfer = (entry["transferType"] as? Number)?.toLong()
            nextResources[id] = resource.transfer(transfer) ?: return mapOf("status" to 1L)
        }
        for (entry in stateChanges) {
            val state = appState(entry) ?: return mapOf("status" to 1L)
            nextAppStates[state.getValue("appStateID")] = state
        }
        resources = nextResources
        appStates = nextAppStates
        return linkedMapOf("status" to 0L, "params" to snapshot())
    }

    /** 缺省数组表示不改变该类状态；存在但不是字典数组时拒绝请求。 */
    private fun entries(
        params: Map<*, *>,
        key: String,
    ): List<Map<*, *>>? {
        if (!params.containsKey(key)) return emptyList()
        val entries = params[key] as? List<*> ?: return null
        if (entries.any { it !is Map<*, *> }) return null
        return entries.filterIsInstance<Map<*, *>>()
    }

    /** 车机的通话、导航和语音状态归车机（entity=2），关闭时归 None。 */
    private fun appState(entry: Map<*, *>): Map<String, Long>? {
        val id = (entry["appStateID"] as? Number)?.toLong() ?: return null
        if (id == 1L) {
            val speech = (entry["speechMode"] as? Number)?.toLong() ?: return null
            if (speech !in listOf(-1L, 1L, 2L)) return null
            return mapOf("appStateID" to id, "entity" to if (speech == -1L) 0L else 2L, "speechMode" to speech)
        }
        if (id != 2L && id != 3L) return null
        val active = entry["state"] as? Boolean ?: return null
        return mapOf("appStateID" to id, "entity" to if (active) 2L else 0L)
    }

    /** entity：0=无人持有、1=手机、2=车机；借用只改变当前持有者。 */
    private data class Resource(
        val entity: Long = 1L,
        val permanentEntity: Long = 1L,
        val borrows: Int = 0,
    ) {
        /** 按车机的 Take/Untake/Borrow/Unborrow 更新，不把释放误当作手机接管。 */
        fun transfer(type: Long?): Resource? =
            when (type) {
                1L -> Resource(2L, 2L)
                2L -> if (permanentEntity == 2L) Resource(0L, 0L) else this
                3L ->
                    when {
                        entity == 2L && permanentEntity == 1L -> copy(borrows = borrows + 1)
                        entity != 2L -> Resource(2L, 1L, 1)
                        else -> null
                    }
                4L ->
                    if (entity == 2L && permanentEntity == 1L) {
                        if (borrows > 1) copy(borrows = borrows - 1) else Resource()
                    } else {
                        this
                    }
                else -> null
            }

        /** 序列化时不暴露内部借用计数。 */
        fun snapshot(id: Long): Map<String, Long> =
            linkedMapOf("resourceID" to id, "entity" to entity, "permanentEntity" to permanentEntity)
    }
}
