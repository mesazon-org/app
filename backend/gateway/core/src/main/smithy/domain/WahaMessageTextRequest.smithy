$version: "2.0"

namespace io.mesazon.gateway.smithy

structure WahaMessageTextRequest {
    @required
    payload: Payload
}

structure Payload {
    @required
    id: String
    @required
    from: String
    @required
    body: String
    @required
    @jsonName("_data")
    data: InternalData
}

structure InternalData {
    @required
    @jsonName("Info")
    info: InternalInfo
}

structure InternalInfo {
    @required
    @jsonName("Sender")
    sender: String
    @required
    @jsonName("SenderAlt")
    senderAlt: String
    @required
    @jsonName("PushName")
    pushName: String
}
