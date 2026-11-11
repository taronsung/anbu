package app.anbu.car.anbu

import app.anbu.car.privacy.BlobStore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/** 차 밖으로 내보내는 유일한 통로. 시제품에서는 데모 릴레이로 HTTP POST 한다. */
interface Transport {
    /** 성공하면 true. 예외를 던지지 않는다. */
    fun postNote(wireJson: String): Boolean

    /** 가족 코드에 묶인 안부를 릴레이에서 지운다. */
    fun deleteFamily(familyCode: String): Boolean

    /**
     * 가족이 보낸 답장을 가져온다. [afterId] 뒤에 온 것만 받는다.
     * @return 답장 한 건씩의 JSON 글. 실패하면 null.
     */
    fun fetchReplies(familyCode: String, afterId: String?): List<String>?
}

/**
 * 보낼 안부를 쌓아 두고, 실패하면 간격을 늘려 가며 다시 보낸다.
 * 터널이나 지하 주차장처럼 통신이 끊긴 곳에서 도착해도 안부가 사라지지 않게 한다.
 */
class Outbox(
    private val store: BlobStore,
    private val transport: Transport,
    /** 이보다 오래 못 보낸 안부는 버린다. 하루 지난 "도착했어요"는 의미가 없다. */
    private val maxAgeMs: Long = 24L * 3600 * 1000,
) {
    data class Pending(val wire: String, val createdMs: Long, val attempts: Int, val nextAttemptMs: Long)

    private val lock = Any()

    fun pending(): List<Pending> = synchronized(lock) { load() }

    fun enqueue(wireJson: String, nowMs: Long) = synchronized(lock) {
        save(load() + Pending(wireJson, nowMs, 0, nowMs))
    }

    /**
     * 보낼 때가 된 것을 보낸다. 네트워크를 쓰므로 화면 스레드 밖에서 부른다.
     * @return 이번에 보내는 데 성공한 건수
     */
    fun flush(nowMs: Long): Int {
        val due = synchronized(lock) {
            val all = load().filter { nowMs - it.createdMs <= maxAgeMs }
            save(all)
            all.filter { it.nextAttemptMs <= nowMs }
        }
        var sent = 0
        for (p in due) {
            val ok = transport.postNote(p.wire)
            synchronized(lock) {
                val all = load().toMutableList()
                val i = all.indexOfFirst { it.wire == p.wire && it.createdMs == p.createdMs }
                if (i >= 0) {
                    if (ok) {
                        all.removeAt(i)
                        sent++
                    } else {
                        val attempts = all[i].attempts + 1
                        all[i] = all[i].copy(attempts = attempts, nextAttemptMs = nowMs + backoffMs(attempts))
                    }
                    save(all)
                }
            }
        }
        return sent
    }

    fun clear() = synchronized(lock) {
        cache = emptyList()
        store.delete(NAME)
    }

    // 저장소 읽기는 복호화가 따르므로, 한 번 읽은 뒤에는 메모리의 사본을 쓴다.
    private var cache: List<Pending>? = null

    private fun load(): List<Pending> {
        cache?.let { return it }
        val text = store.read(NAME)
        val list = if (text == null) emptyList() else runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Pending(o.getString("wire"), o.getLong("createdMs"), o.getInt("attempts"), o.getLong("nextAttemptMs"))
            }
        }.getOrElse { emptyList() }
        cache = list
        return list
    }

    private fun save(list: List<Pending>) {
        if (list == cache) return
        cache = list
        if (list.isEmpty()) {
            store.delete(NAME)
            return
        }
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("wire", it.wire).put("createdMs", it.createdMs)
                    .put("attempts", it.attempts).put("nextAttemptMs", it.nextAttemptMs)
            )
        }
        store.write(NAME, arr.toString())
    }

    companion object {
        const val NAME = "outbox"

        /** 5초, 10초, 20초 ... 최대 10분. */
        fun backoffMs(attempts: Int): Long =
            min(5_000L shl (attempts - 1).coerceIn(0, 10), 10 * 60_000L)
    }
}
