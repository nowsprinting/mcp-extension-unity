package com.nowsprinting.mcp_extension_unity

import com.nowsprinting.mcp_extension_unity.model.UnityCompilationMcpModel
import com.jetbrains.rd.framework.IProtocol
import com.jetbrains.rd.framework.RdId
import java.util.concurrent.ConcurrentHashMap

internal object UnityCompilationMcpModelProvider {
    private val models = ConcurrentHashMap<IProtocol, UnityCompilationMcpModel>()

    fun getOrBindModel(protocol: IProtocol): UnityCompilationMcpModel {
        return models.computeIfAbsent(protocol) { proto ->
            val ctor = UnityCompilationMcpModel::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            val model = ctor.newInstance() as UnityCompilationMcpModel
            // Match C# generated constructor: Identify + BindTopLevel
            // RdId.Null (Kotlin) == RdId.Root (C#) == RdId(0)
            model.identify(proto.identity, RdId.Null.mix("UnityCompilationMcpModel"))
            model.preBind(proto.lifetime, proto, "UnityCompilationMcpModel")
            model.bind()
            proto.lifetime.onTermination { models.remove(proto) }
            model
        }
    }
}
