package app.anbu.car.privacy

import org.json.JSONObject

/**
 * 동의 화면에 그대로 보여 주는 항목. 화면 문구와 실제 동작이 어긋나지 않도록
 * 한 곳에 정의하고, 화면·README·테스트가 모두 이 목록을 본다.
 */
enum class ConsentItem(
    val required: Boolean,
    val title: String,
    val collected: String,
    val purpose: String,
    val retention: String,
) {
    DRIVING_SUMMARY(
        required = true,
        title = "주행 요약 만들기",
        collected = "안전띠, 제동, 와이퍼, 전조등, 주행거리, 속도, 기어, 내비게이션 주의 알림",
        purpose = "주간 운전 일기를 만들어 본인에게 먼저 보여 드립니다",
        retention = "차 안에만 암호화해 9주 보관한 뒤 자동으로 지웁니다",
    ),
    ARRIVAL_NOTE(
        required = false,
        title = "도착·출발 안부 보내기",
        collected = "도착 시각, 내가 붙인 장소 이름(집, 병원 등)",
        purpose = "이름을 붙인 곳에 도착하면 가족에게 알립니다. 위치 좌표와 경로는 보내지 않습니다",
        retention = "가족 화면에 30일 보관합니다",
    ),
    PLACE_PROXIMITY(
        required = false,
        title = "저장한 장소 도착 알아채기",
        collected = "저장한 장소 근처에 들어왔는지 여부",
        purpose = "내비게이션 없이 가도 도착을 알아챕니다. 좌표는 차 밖으로 나가지 않습니다",
        retention = "저장하지 않습니다",
    ),
    CHECK_IN_ALERT(
        required = false,
        title = "괜찮으세요 확인",
        collected = "에어백 작동 여부, 급하게 멈춘 뒤 정지 시간",
        purpose = "물음에 30초 동안 답이 없으면 가족에게 시각을 알립니다",
        retention = "가족 화면에 30일 보관합니다",
    );
}

/** 가족에게 보이는 운전자 호칭. 이름은 받지 않는다. */
val WHO_CHOICES = listOf("아버지", "어머니", "할아버지", "할머니")

data class ConsentState(
    val granted: Boolean = false,
    val grantedAtMs: Long = 0,
    val policyVersion: Int = POLICY_VERSION,
    val who: String = WHO_CHOICES[0],
    /** 가족 초대 코드. 가족 화면은 이 코드로 묶인 안부만 본다. */
    val familyCode: String = "",
    val shareArrival: Boolean = false,
    val useProximity: Boolean = false,
    val checkInAlert: Boolean = false,
    /** 이름을 붙이지 않은 곳에 세웠을 때도 "외출지에 도착"으로 알릴지. 기본은 알리지 않는다. */
    val shareUnnamedArrival: Boolean = false,
    /** 가족이 정해 둔 답장 가운데 하나를 골라 보내면 받을지. 받는 것은 답장의 이름과 시각뿐이다. */
    val receiveReplies: Boolean = true,
    /** 도착 안부에 "평소보다 오래 걸렸는지"를 붙일지. */
    val shareTripDuration: Boolean = false,
    /** 주간 일기를 나눌 때 세부 수치까지 보낼지. 기본은 단계와 한 줄뿐이다. */
    val shareWeeklyDetails: Boolean = false,
    /** 괜찮으세요 알림에 떠나온 곳의 이름("병원을 떠난 뒤")을 붙일지. 좌표는 보내지 않는다. */
    val checkInPlaceLabel: Boolean = false,
    /** "오늘은 쉬기"가 끝나는 시각. 이 시각 전에는 기록도 전송도 하지 않는다. */
    val pausedUntilMs: Long = 0,
    /** "이번 운전만 쉬기". 다른 사람이 운전할 때 켠다. 그 주행이 끝나 주차하면 저절로 풀린다. */
    val skipTrip: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("granted", granted)
        .put("grantedAtMs", grantedAtMs)
        .put("policyVersion", policyVersion)
        .put("who", who)
        .put("familyCode", familyCode)
        .put("shareArrival", shareArrival)
        .put("useProximity", useProximity)
        .put("shareUnnamedArrival", shareUnnamedArrival)
        .put("receiveReplies", receiveReplies)
        .put("checkInAlert", checkInAlert)
        .put("shareTripDuration", shareTripDuration)
        .put("shareWeeklyDetails", shareWeeklyDetails)
        .put("checkInPlaceLabel", checkInPlaceLabel)
        .put("pausedUntilMs", pausedUntilMs)
        .put("skipTrip", skipTrip)

    companion object {
        const val POLICY_VERSION = 1

        fun fromJson(o: JSONObject) = ConsentState(
            granted = o.optBoolean("granted", false),
            grantedAtMs = o.optLong("grantedAtMs", 0),
            policyVersion = o.optInt("policyVersion", POLICY_VERSION),
            who = o.optString("who", WHO_CHOICES[0]),
            familyCode = o.optString("familyCode", ""),
            shareArrival = o.optBoolean("shareArrival", false),
            useProximity = o.optBoolean("useProximity", false),
            shareUnnamedArrival = o.optBoolean("shareUnnamedArrival", false),
            receiveReplies = o.optBoolean("receiveReplies", true),
            checkInAlert = o.optBoolean("checkInAlert", false),
            shareTripDuration = o.optBoolean("shareTripDuration", false),
            shareWeeklyDetails = o.optBoolean("shareWeeklyDetails", false),
            checkInPlaceLabel = o.optBoolean("checkInPlaceLabel", false),
            pausedUntilMs = o.optLong("pausedUntilMs", 0),
            skipTrip = o.optBoolean("skipTrip", false),
        )
    }
}

enum class Outbound { DEPARTURE, ARRIVAL, WEEKLY, CHECK_IN }

/**
 * 기록과 전송의 관문. 엔진은 무엇을 저장하거나 내보내기 전에 반드시 여기를 거친다.
 * 동의가 없거나 쉬는 중이면 아무것도 통과하지 못한다.
 */
class PrivacyGate(private val state: () -> ConsentState) {

    /** 오늘은 쉬기, 또는 이번 운전만 쉬기가 걸려 있는가. */
    fun isPaused(nowMs: Long): Boolean = state().let { nowMs < it.pausedUntilMs || it.skipTrip }

    fun canRecord(nowMs: Long): Boolean {
        val s = state()
        return s.granted && !isPaused(nowMs)
    }

    fun canSend(kind: Outbound, nowMs: Long): Boolean {
        val s = state()
        if (!s.granted || isPaused(nowMs) || s.familyCode.isEmpty()) return false
        return when (kind) {
            // 출발 한마디와 주간 일기 나누기는 매번 본인이 직접 누르는 동작이다.
            Outbound.DEPARTURE -> s.shareArrival
            Outbound.ARRIVAL -> s.shareArrival
            Outbound.WEEKLY -> true
            Outbound.CHECK_IN -> s.checkInAlert
        }
    }

    /** 가족의 답장을 받아도 되는가. 쉬는 동안에는 받지도 읽지도 않는다. */
    fun canReceiveReplies(nowMs: Long): Boolean {
        val s = state()
        return s.granted && !isPaused(nowMs) && s.familyCode.isNotEmpty() && s.receiveReplies
    }

    fun canUseProximity(nowMs: Long): Boolean {
        val s = state()
        return s.granted && s.useProximity && !isPaused(nowMs)
    }
}
