import Foundation
import HealthKit
import WatchKit

/// 워치 앱은 손목을 내리면 곧 백그라운드로 가고 블루투스 연결이 끊길 수 있다.
/// 이를 막을 수 있는 방법이 둘 있고, 어느 쪽이 실제로 연결을 오래 유지하는지는 시험해 봐야 한다.
enum KeepAliveMode: String, CaseIterable, Identifiable {
    case none = "유지 안 함"
    case extended = "확장 런타임"
    case workout = "운동 세션"

    var id: String { rawValue }
}

final class KeepAlive: NSObject, ObservableObject {
    @Published private(set) var active: KeepAliveMode = .none

    private let store = HKHealthStore()
    private var workoutSession: HKWorkoutSession?
    private var workoutBuilder: HKWorkoutBuilder?
    private var extendedSession: WKExtendedRuntimeSession?
    private var startedAt: Date?

    func start(_ mode: KeepAliveMode) {
        stop()
        switch mode {
        case .none:
            break
        case .extended:
            let s = WKExtendedRuntimeSession()
            s.delegate = self
            s.start()
            extendedSession = s
        case .workout:
            startWorkout()
        }
        if mode != .none {
            startedAt = Date()
            AppLog.shared.add("유지 모드 시작: \(mode.rawValue)")
        }
        DispatchQueue.main.async { self.active = mode }
    }

    func stop() {
        if let s = extendedSession {
            s.invalidate()
            extendedSession = nil
        }
        if let w = workoutSession {
            w.end()
            workoutSession = nil
            // 운동 기록이 남지 않도록 버린다.
            workoutBuilder?.endCollection(withEnd: Date()) { [weak self] _, _ in
                self?.workoutBuilder?.discardWorkout()
                self?.workoutBuilder = nil
            }
        }
        if active != .none, let t = startedAt {
            AppLog.shared.add("유지 모드 종료: \(active.rawValue), \(Int(Date().timeIntervalSince(t) / 60))분 유지")
        }
        startedAt = nil
        DispatchQueue.main.async { self.active = .none }
    }

    private func startWorkout() {
        guard HKHealthStore.isHealthDataAvailable() else {
            AppLog.shared.add("이 기기에서는 HealthKit 을 쓸 수 없음")
            return
        }
        let types: Set<HKSampleType> = [HKObjectType.workoutType()]
        store.requestAuthorization(toShare: types, read: []) { [weak self] ok, error in
            guard let self = self else { return }
            guard ok else {
                AppLog.shared.add("운동 권한이 거부됨: \(error?.localizedDescription ?? "")")
                return
            }
            DispatchQueue.main.async {
                let config = HKWorkoutConfiguration()
                config.activityType = .other
                config.locationType = .indoor
                do {
                    let session = try HKWorkoutSession(healthStore: self.store, configuration: config)
                    session.delegate = self
                    let builder = HKWorkoutBuilder(healthStore: self.store, configuration: config, device: .local())
                    self.workoutSession = session
                    self.workoutBuilder = builder
                    session.startActivity(with: Date())
                    builder.beginCollection(withStart: Date()) { _, _ in }
                } catch {
                    AppLog.shared.add("운동 세션 시작 실패: \(error.localizedDescription)")
                }
            }
        }
    }
}

extension KeepAlive: HKWorkoutSessionDelegate {
    func workoutSession(
        _ workoutSession: HKWorkoutSession,
        didChangeTo toState: HKWorkoutSessionState,
        from fromState: HKWorkoutSessionState,
        date: Date
    ) {
        AppLog.shared.add("운동 세션 상태: \(fromState.rawValue) -> \(toState.rawValue)")
    }

    func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {
        AppLog.shared.add("운동 세션 오류: \(error.localizedDescription)")
    }
}

extension KeepAlive: WKExtendedRuntimeSessionDelegate {
    func extendedRuntimeSessionDidStart(_ extendedRuntimeSession: WKExtendedRuntimeSession) {
        AppLog.shared.add("확장 런타임 세션 시작됨")
    }

    func extendedRuntimeSessionWillExpire(_ extendedRuntimeSession: WKExtendedRuntimeSession) {
        AppLog.shared.add("확장 런타임 세션이 곧 만료됨")
    }

    func extendedRuntimeSession(
        _ extendedRuntimeSession: WKExtendedRuntimeSession,
        didInvalidateWith reason: WKExtendedRuntimeSessionInvalidationReason,
        error: Error?
    ) {
        AppLog.shared.add("확장 런타임 세션 종료: 이유 \(reason.rawValue)")
        DispatchQueue.main.async {
            self.extendedSession = nil
            if self.active == .extended { self.active = .none }
        }
    }
}
