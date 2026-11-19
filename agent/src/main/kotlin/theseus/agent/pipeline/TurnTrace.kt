package theseus.agent.pipeline

import org.slf4j.LoggerFactory
import theseus.agent.llm.LlmException
import theseus.agent.llm.ToolCall
import java.util.UUID

/** Трасса хода: только технические этапы и разрешённые имена, без текстов и аргументов модели. */
internal class TurnTrace(
    private val emit: (String) -> Unit = { LoggerFactory.getLogger(TurnTrace::class.java).info(it) },
) {
    private val id = UUID.randomUUID()
    private var stage = "access"
    private var tool = "none"

    fun started(userId: Long) {
        emit("Диалог: trace_id=$id, user_id=$userId, stage=start")
    }

    fun mark(value: String) {
        stage = value
    }

    fun model(role: String) {
        stage = "llm"
        emit("Диалог: trace_id=$id, stage=$stage, role=${if (role == "summary") "summary" else "main"}")
    }

    fun selected(
        call: ToolCall,
        topic: String?,
    ) {
        executing(call)
        emit("Диалог: trace_id=$id, stage=$stage, tool=$tool, topic=${topic ?: "none"}")
    }

    fun executing(call: ToolCall) {
        tool = call.name.takeIf { it in TOOLS } ?: "unknown"
        stage = "tool"
    }

    fun stopped(reason: String) {
        emit("Диалог: trace_id=$id, stage=$stage, tool=$tool, reason=$reason")
    }

    fun failed(error: Exception) {
        // Throwable/message не передаются логгеру: они могут содержать ответ провайдера или пользовательский ввод.
        val origin = error.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "unknown"
        val provider = (error as? LlmException)?.let { ", llm_reason=${it.reason}, http_status=${it.httpStatus ?: "none"}" }.orEmpty()
        emit("Диалог: trace_id=$id, stage=$stage, tool=$tool, error=${error.javaClass.simpleName}, origin=$origin$provider")
    }

    companion object {
        private val TOOLS =
            setOf(
                "assistant_reply",
                "resolve_reference",
                "create_reminder",
                "list_reminders",
                "find_reminders",
                "get_reminder",
                "update_reminder",
                "delete_reminder",
                "snooze_reminder",
                "pause_reminder",
                "resume_reminder",
            )
    }
}
