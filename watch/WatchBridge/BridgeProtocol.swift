import CoreBluetooth
import Foundation

/// 갤럭시 <-> 워치 BLE 규약. 갤럭시 쪽 android/.../Protocol.kt 와 값이 같아야 한다. (docs/PROTOCOL.md)
enum BridgeProtocol {
    static let service = CBUUID(string: "5F1D0001-8C3B-4C2A-9A1E-2B7F6D3A0C11")
    static let notify = CBUUID(string: "5F1D0002-8C3B-4C2A-9A1E-2B7F6D3A0C11")
    static let control = CBUUID(string: "5F1D0003-8C3B-4C2A-9A1E-2B7F6D3A0C11")
}

/// 갤럭시가 보내는 JSON 한 건.
struct BridgeMessage: Decodable {
    var v: Int?
    var t: String
    var app: String?
    var from: String?
    var room: String?
    var text: String?
    var ts: Int64?
    var n: Int?
}

/// 프레임 = [msgId][seq | 마지막이면 0x80] + 조각. 조각을 모아 한 건의 payload 로 만든다.
struct FrameAssembler {
    private var currentID: UInt8?
    private var expectedSeq = 0
    private var buffer = Data()

    /// 프레임을 하나 넣는다. 한 건이 완성되면 payload 를 돌려준다.
    mutating func feed(_ frame: Data) -> Data? {
        let bytes = [UInt8](frame)
        guard bytes.count >= 2 else { return nil }
        let id = bytes[0]
        let seq = Int(bytes[1] & 0x7F)
        let isLast = (bytes[1] & 0x80) != 0

        if seq == 0 {
            currentID = id
            expectedSeq = 0
            buffer = Data()
        }
        guard currentID == id, seq == expectedSeq else {
            reset()
            return nil
        }
        buffer.append(contentsOf: bytes[2...])
        expectedSeq += 1
        if isLast {
            let payload = buffer
            reset()
            return payload
        }
        return nil
    }

    private mutating func reset() {
        currentID = nil
        expectedSeq = 0
        buffer = Data()
    }
}
