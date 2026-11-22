import CoreBluetooth
import Foundation

/// 워치가 BLE "중앙(Central)" 이 되어 갤럭시(주변기기)에 접속하고 알림을 받는다.
final class BLEClient: NSObject, ObservableObject {
    @Published private(set) var status = "시작 중"
    @Published private(set) var lastReceived: Date?
    @Published private(set) var lastHeartbeat: Date?
    @Published private(set) var messageCount = 0

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var controlCharacteristic: CBCharacteristic?
    private var assembler = FrameAssembler()
    private let rememberedKey = "galaxyPeripheralID"
    private var lastEventAt = Date()

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: nil)
    }

    // MARK: - 접속

    private func connectOrScan() {
        guard central.state == .poweredOn else { return }
        // 1) 이미 시스템이 연결해 둔 기기
        if let p = central.retrieveConnectedPeripherals(withServices: [BridgeProtocol.service]).first {
            AppLog.shared.add("이미 연결된 갤럭시를 찾음")
            connect(p)
            return
        }
        // 2) 전에 연결했던 기기를 기억해 두었다면 바로 접속 요청 (나타날 때까지 대기)
        if let text = UserDefaults.standard.string(forKey: rememberedKey),
           let id = UUID(uuidString: text),
           let p = central.retrievePeripherals(withIdentifiers: [id]).first {
            AppLog.shared.add("기억해 둔 갤럭시에 접속 요청")
            connect(p)
            return
        }
        // 3) 처음이면 스캔
        status = "갤럭시 찾는 중"
        AppLog.shared.add("갤럭시 스캔 시작")
        central.scanForPeripherals(withServices: [BridgeProtocol.service], options: nil)
    }

    private func connect(_ p: CBPeripheral) {
        peripheral = p
        p.delegate = self
        status = "접속 중"
        central.connect(p, options: nil) // 시간 제한 없이 대기하는 접속 요청
    }

    /// 기억해 둔 갤럭시를 잊고 다시 스캔한다. (갤럭시를 바꿨을 때)
    func forgetAndRescan() {
        UserDefaults.standard.removeObject(forKey: rememberedKey)
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        peripheral = nil
        central.stopScan()
        AppLog.shared.add("기억한 갤럭시를 잊음")
        connectOrScan()
    }

    // MARK: - 수신 처리

    private func handle(payload: Data) {
        guard let message = try? JSONDecoder().decode(BridgeMessage.self, from: payload) else {
            AppLog.shared.add("해석할 수 없는 데이터 \(payload.count)바이트")
            return
        }
        let now = Date()
        let gap = now.timeIntervalSince(lastEventAt)
        lastEventAt = now
        DispatchQueue.main.async { self.lastReceived = now }

        switch message.t {
        case "hb":
            DispatchQueue.main.async { self.lastHeartbeat = now }
            // 하트비트는 15초마다 오므로, 간격이 크게 벌어졌을 때만 기록해 연결이 얼마나 끊겼는지 본다.
            if gap > 45 { AppLog.shared.add("하트비트 재개 (공백 \(Int(gap))초)") }
        case "msg":
            DispatchQueue.main.async { self.messageCount += 1 }
            AppLog.shared.add("메시지 수신 (\(message.from ?? "?"))")
            LocalNotifier.shared.post(
                from: message.from ?? "",
                room: message.room ?? "",
                text: message.text ?? ""
            )
        case "hello":
            AppLog.shared.add("갤럭시와 인사 완료")
        default:
            AppLog.shared.add("알 수 없는 종류: \(message.t)")
        }
    }

    private func sendHello() {
        guard let p = peripheral, let c = controlCharacteristic else { return }
        let data = Data(#"{"v":1,"t":"hello","app":"watch-bridge-watch"}"#.utf8)
        p.writeValue(data, for: c, type: .withoutResponse)
    }
}

// MARK: - CBCentralManagerDelegate

extension BLEClient: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            AppLog.shared.add("블루투스 사용 가능")
            connectOrScan()
        case .unauthorized:
            status = "블루투스 권한 필요"
            AppLog.shared.add("블루투스 권한이 없음")
        case .poweredOff:
            status = "블루투스 꺼짐"
        default:
            status = "블루투스 사용 불가"
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        AppLog.shared.add("갤럭시 발견")
        central.stopScan()
        UserDefaults.standard.set(peripheral.identifier.uuidString, forKey: rememberedKey)
        connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        AppLog.shared.add("접속됨")
        status = "접속됨, 서비스 찾는 중"
        assembler = FrameAssembler()
        peripheral.discoverServices([BridgeProtocol.service])
    }

    func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        AppLog.shared.add("접속 실패: \(error?.localizedDescription ?? "알 수 없음")")
        connect(peripheral)
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        AppLog.shared.add("연결 끊김: \(error?.localizedDescription ?? "정상 종료")")
        status = "끊김, 다시 접속 대기"
        controlCharacteristic = nil
        connect(peripheral) // 갤럭시가 다시 나타나면 자동으로 이어진다
    }
}

// MARK: - CBPeripheralDelegate

extension BLEClient: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == BridgeProtocol.service }) else {
            AppLog.shared.add("서비스를 찾지 못함: \(error?.localizedDescription ?? "")")
            return
        }
        peripheral.discoverCharacteristics([BridgeProtocol.notify, BridgeProtocol.control], for: service)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        for c in service.characteristics ?? [] {
            if c.uuid == BridgeProtocol.notify {
                // 처음에는 이 구독 때문에 페어링 창이 뜰 수 있다.
                peripheral.setNotifyValue(true, for: c)
            } else if c.uuid == BridgeProtocol.control {
                controlCharacteristic = c
            }
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        if let error = error {
            status = "구독 실패"
            AppLog.shared.add("구독 실패: \(error.localizedDescription)")
            return
        }
        if characteristic.isNotifying {
            status = "연결됨"
            AppLog.shared.add("알림 구독 시작")
            sendHello()
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard error == nil, let frame = characteristic.value else { return }
        if let payload = assembler.feed(frame) {
            handle(payload: payload)
        }
    }
}
