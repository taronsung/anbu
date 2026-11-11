package app.anbu.car.voice

enum class VoiceCommand {
    /** "보내지 마" */
    CANCEL_SEND,
    /** "지금 보내" */
    SEND_NOW,
    /** "괜찮아" */
    IM_OK,
    /** "안 괜찮아", "도와줘" */
    NEED_HELP,
    /** "출발한다고 전해 줘" */
    SEND_DEPARTURE,
    /** "도착했다고 전해 줘" */
    SEND_ARRIVAL,
    /** "운전 일기 읽어 줘" */
    READ_DIARY,
    NONE,
}

/**
 * 인식된 문장에서 명령을 고른다. 띄어쓰기와 존댓말 어미가 달라도 받도록
 * 공백과 문장부호를 지운 뒤 핵심 낱말만 본다.
 */
object VoiceCommands {
    fun parse(text: String): VoiceCommand {
        val t = text.lowercase().filter { it.isLetterOrDigit() }
        if (t.isEmpty()) return VoiceCommand.NONE

        val negatedOk = listOf("안괜찮", "괜찮지않", "괜찮지못", "못괜찮").any { it in t }
        val help = listOf("도와", "살려", "아파", "아프", "다쳤", "119").any { it in t }
        if (negatedOk || help) return VoiceCommand.NEED_HELP
        if (listOf("괜찮", "문제없", "멀쩡", "이상없").any { it in t }) return VoiceCommand.IM_OK

        if (listOf("보내지마", "보내지말", "안보내", "취소", "하지마", "하지말", "그만").any { it in t }) {
            return VoiceCommand.CANCEL_SEND
        }
        val tell = listOf("전해", "알려", "보내", "말해").any { it in t }
        if ("출발" in t && tell) return VoiceCommand.SEND_DEPARTURE
        if ("도착" in t && tell) return VoiceCommand.SEND_ARRIVAL
        if ("일기" in t) return VoiceCommand.READ_DIARY
        if (listOf("지금보내", "바로보내", "보내줘", "보내").any { it in t }) return VoiceCommand.SEND_NOW
        return VoiceCommand.NONE
    }
}
