package me.kalfa.agentconsole.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.kalfa.agentconsole.domain.model.TranscriptLine

/**
 * Live captions for active AI calls over Supabase Realtime **Broadcast**.
 * The VoxEngine bridge forwards ElevenLabs UserTranscript/AgentResponse events
 * to channel "call:{call_attempt_id}", event "transcript" (see the management
 * plan). No DB writes — ephemeral by design.
 */
class LiveTranscriptManager(private val client: SupabaseClient) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val channels = mutableMapOf<String, RealtimeChannel>()
    private val jobs = mutableMapOf<String, Job>()

    private val _transcripts = MutableStateFlow<Map<String, List<TranscriptLine>>>(emptyMap())
    val transcripts: StateFlow<Map<String, List<TranscriptLine>>> = _transcripts.asStateFlow()

    /** Keep subscriptions in sync with the set of currently-active AI calls. */
    fun sync(activeCallIds: Set<String>) {
        scope.launch {
            // Unsubscribe ended calls and drop their lines
            (channels.keys - activeCallIds).forEach { gone ->
                jobs.remove(gone)?.cancel()
                channels.remove(gone)?.let { ch ->
                    try { client.realtime.removeChannel(ch) } catch (_: Exception) {}
                }
                _transcripts.update { it - gone }
            }
            // Subscribe new calls
            (activeCallIds - channels.keys).forEach { id ->
                try {
                    val ch = client.realtime.channel("call:$id")
                    val flow = ch.broadcastFlow<TranscriptLine>(event = "transcript")
                    // collect BEFORE subscribe — events are lost otherwise
                    jobs[id] = scope.launch {
                        flow.collect { line ->
                            _transcripts.update { all ->
                                val cur = all[id].orEmpty() + line
                                all + (id to cur.takeLast(30))
                            }
                        }
                    }
                    ch.subscribe()
                    channels[id] = ch
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
