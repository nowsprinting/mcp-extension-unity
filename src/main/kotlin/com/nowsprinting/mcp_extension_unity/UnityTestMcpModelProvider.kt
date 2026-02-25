package com.nowsprinting.mcp_extension_unity

import com.nowsprinting.mcp_extension_unity.model.UnityTestMcpModel
import com.jetbrains.rd.framework.IProtocol
import com.jetbrains.rd.framework.RdId
import java.util.concurrent.ConcurrentHashMap

internal object UnityTestMcpModelProvider {
    private val models = ConcurrentHashMap<IProtocol, UnityTestMcpModel>()

    fun getOrBindModel(protocol: IProtocol): UnityTestMcpModel {
        return models.computeIfAbsent(protocol) { proto ->
            val ctor = UnityTestMcpModel::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            val model = ctor.newInstance() as UnityTestMcpModel
            // Match C# generated constructor: Identify + BindTopLevel
            // RdId.Null (Kotlin) == RdId.Root (C#) == RdId(0)
            model.identify(proto.identity, RdId.Null.mix("UnityTestMcpModel"))
            model.preBind(proto.lifetime, proto, "UnityTestMcpModel")
            model.bind()
            proto.lifetime.onTermination { models.remove(proto) }
            model
        }
    }
}
