package dev.watchbridge.galaxy

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * 카카오톡 알림을 읽어 BridgeService 로 넘긴다.
 * 사용자가 설정 > 알림 접근 권한에서 직접 허용해야 동작한다.
 *
 * 카톡 알림의 구조(제목/본문/대화방)는 기기·버전마다 조금씩 달라서 실제 기기에서 확인이 필요하다.
 */
class KakaoListener : NotificationListenerService() {

    /** 같은 알림이 갱신될 때(읽음 처리 등) 같은 메시지를 또 보내지 않기 위한 기록. */
    private val lastSignature = HashMap<String, String>()

    override fun onListenerConnected() {
        EventLog.add("카톡 알림 읽기 연결됨")
    }

    override fun onListenerDisconnected() {
        EventLog.add("카톡 알림 읽기 연결 끊김")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != KAKAO_PACKAGE) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras

        val last = lastMessageBundle(extras)
        val text = (last?.getCharSequence("text")
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        if (text.isNullOrBlank()) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val sender = last?.getCharSequence("sender")?.toString()
            ?: last?.getBundle("sender_person")?.getCharSequence("name")?.toString()
            ?: title
        val room = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        val signature = "$sender|$text|${n.`when`}"
        if (lastSignature[sbn.key] == signature) return
        lastSignature[sbn.key] = signature
        if (lastSignature.size > 100) lastSignature.clear()

        val json = JSONObject()
            .put("v", 1)
            .put("t", "msg")
            .put("app", "kakao")
            .put("from", clip(sender ?: "", 40))
            .put("room", clip(room ?: "", 40))
            .put("text", clip(text, 200))
            .put("ts", sbn.postTime)
        BridgeService.post(json)
        EventLog.add("카톡 수신(${sender ?: "?"}), ${text.length}자")
    }

    @Suppress("DEPRECATION")
    private fun lastMessageBundle(extras: Bundle): Bundle? {
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        return messages.lastOrNull() as? Bundle
    }

    private fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        var end = max
        if (Character.isHighSurrogate(s[end - 1])) end-- // 이모지가 반으로 잘리지 않게
        return s.substring(0, end) + "…"
    }

    companion object {
        const val KAKAO_PACKAGE = "com.kakao.talk"
    }
}
