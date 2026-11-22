import Foundation

/// 화면에 보여 주고 저장도 하는 짧은 기록. 백그라운드 생존 시험 결과를 나중에 보려고 파일 대신 UserDefaults 에 남긴다.
/// 메시지 내용은 기록하지 않는다.
final class AppLog: ObservableObject {
    static let shared = AppLog()

    @Published private(set) var lines: [String]
    private let key = "appLog"
    private let maxLines = 300
    private let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MM-dd HH:mm:ss"
        return f
    }()

    private init() {
        lines = UserDefaults.standard.stringArray(forKey: key) ?? []
    }

    func add(_ message: String) {
        let line = "\(formatter.string(from: Date()))  \(message)"
        DispatchQueue.main.async {
            self.lines.append(line)
            if self.lines.count > self.maxLines {
                self.lines.removeFirst(self.lines.count - self.maxLines)
            }
            UserDefaults.standard.set(self.lines, forKey: self.key)
        }
    }

    func clear() {
        DispatchQueue.main.async {
            self.lines = []
            UserDefaults.standard.removeObject(forKey: self.key)
        }
    }
}
