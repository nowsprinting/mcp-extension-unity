package com.nowsprinting.mcp_extension_unity

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.rd.util.reactive.valueOrDefault
import com.jetbrains.rd.util.threading.coroutines.asCoroutineDispatcher
import com.jetbrains.rider.plugins.unity.isConnectedToEditor
import com.jetbrains.rider.plugins.unity.model.frontendBackend.frontendBackendModel
import com.jetbrains.rider.projectView.solution
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

class PlayControlToolset : McpToolset {

    private val LOG = Logger.getInstance(PlayControlToolset::class.java)

    companion object {
        enum class PlayAction { PLAY, STOP, PAUSE, RESUME, STEP, STATUS }

        internal fun parseAction(action: String?): PlayAction? {
            if (action == null) return null
            return when (action.trim().lowercase()) {
                "play" -> PlayAction.PLAY
                "stop" -> PlayAction.STOP
                "pause" -> PlayAction.PAUSE
                "resume" -> PlayAction.RESUME
                "step" -> PlayAction.STEP
                "status" -> PlayAction.STATUS
                else -> null
            }
        }
    }

    @McpTool(name = "unity_play_control")
    @McpDescription(description = """
        Control Unity Editor's play mode. Requires Unity Editor to be connected to Rider.
        Actions: play (enter play mode), stop (exit play mode), pause, resume, step (advance one frame), status (read-only).
    """)
    suspend fun unity_play_control(
        @McpDescription(description = "Action to perform: play, stop, pause, resume, step, status")
        action: String? = null
    ): PlayControlResult {
        val parsedAction = parseAction(action)
            ?: return PlayControlErrorResult(
                errorMessage = if (action == null)
                    "action is required. Valid values: play, stop, pause, resume, step, status."
                else
                    "Invalid action: '$action'. Valid values: play, stop, pause, resume, step, status."
            )

        try {
            val project = currentCoroutineContext().project
            if (!project.isConnectedToEditor()) {
                return PlayControlErrorResult(errorMessage = "Unity Editor is not connected to Rider.")
            }

            val solution = project.solution
            val protocol = solution.protocol
                ?: return PlayControlErrorResult(
                    errorMessage = "No protocol available. The solution may not be fully loaded."
                )

            return withContext(protocol.scheduler.asCoroutineDispatcher) {
                val playControls = solution.frontendBackendModel.playControls

                when (parsedAction) {
                    PlayAction.PLAY -> playControls.play.set(true)
                    PlayAction.STOP -> playControls.play.set(false)
                    PlayAction.PAUSE -> playControls.pause.set(true)
                    PlayAction.RESUME -> playControls.pause.set(false)
                    PlayAction.STEP -> playControls.step.fire(Unit)
                    PlayAction.STATUS -> { /* read-only, no state change */ }
                }

                PlayControlSuccessResult(
                    action = parsedAction.name.lowercase(),
                    isPlaying = playControls.play.valueOrDefault(false),
                    isPaused = playControls.pause.valueOrDefault(false)
                )
            }
        } catch (e: Exception) {
            LOG.error("unity_play_control failed", e)
            return PlayControlErrorResult(errorMessage = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

@Serializable(with = PlayControlResultSerializer::class)
sealed interface PlayControlResult

data class PlayControlErrorResult(
    val errorMessage: String
) : PlayControlResult

data class PlayControlSuccessResult(
    val action: String,
    val isPlaying: Boolean,
    val isPaused: Boolean
) : PlayControlResult

object PlayControlResultSerializer : KSerializer<PlayControlResult> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("PlayControlResult")

    override fun serialize(encoder: Encoder, value: PlayControlResult) {
        val jsonEncoder = encoder as JsonEncoder
        val jsonObject = when (value) {
            is PlayControlErrorResult -> buildJsonObject {
                put("success", false)
                put("errorMessage", value.errorMessage)
            }
            is PlayControlSuccessResult -> buildJsonObject {
                put("success", true)
                put("action", value.action)
                put("isPlaying", value.isPlaying)
                put("isPaused", value.isPaused)
            }
        }
        jsonEncoder.encodeJsonElement(jsonObject)
    }

    override fun deserialize(decoder: Decoder): PlayControlResult {
        throw UnsupportedOperationException("Deserialization not supported")
    }
}
