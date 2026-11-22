import SwiftUI

struct ContentView: View {
    @ObservedObject var client: BLEClient
    @ObservedObject var keepAlive: KeepAlive
    @ObservedObject private var log = AppLog.shared
    @State private var selected: KeepAliveMode = .none

    var body: some View {
        List {
            Section("연결") {
                Text(client.status).font(.headline)
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    Text(ageText(client.lastReceived, now: context.date, label: "마지막 수신"))
                    Text(ageText(client.lastHeartbeat, now: context.date, label: "마지막 하트비트"))
                }
                .font(.footnote)
                Text("받은 메시지 \(client.messageCount)건").font(.footnote)
                Button("갤럭시 다시 찾기") { client.forgetAndRescan() }
            }

            Section("백그라운드 유지 (시험)") {
                Picker("모드", selection: $selected) {
                    ForEach(KeepAliveMode.allCases) { Text($0.rawValue).tag($0) }
                }
                Button(keepAlive.active == .none ? "적용" : "중지") {
                    if keepAlive.active == .none {
                        keepAlive.start(selected)
                    } else {
                        keepAlive.stop()
                    }
                }
                if keepAlive.active != .none {
                    Text("실행 중: \(keepAlive.active.rawValue)").font(.footnote)
                }
            }

            Section("기록") {
                ForEach(Array(log.lines.suffix(40).reversed().enumerated()), id: \.offset) { _, line in
                    Text(line).font(.system(size: 11, design: .monospaced))
                }
                Button("기록 지우기") { log.clear() }
            }
        }
    }

    private func ageText(_ date: Date?, now: Date, label: String) -> String {
        guard let date = date else { return "\(label): 없음" }
        let seconds = Int(now.timeIntervalSince(date))
        if seconds < 60 { return "\(label): \(seconds)초 전" }
        if seconds < 3600 { return "\(label): \(seconds / 60)분 전" }
        return "\(label): \(seconds / 3600)시간 \((seconds % 3600) / 60)분 전"
    }
}
