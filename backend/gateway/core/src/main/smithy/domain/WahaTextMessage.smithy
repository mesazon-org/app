$version: "2.0"

namespace io.rikkos.gateway.smithy

structure WahaMessageText {
    @required
    id: String
    @required
    timestamp: Long
    @required
    event: String
    @required
    session: String
    @required
    me: Me
    @required
    payload: Payload
}

structure Me {
    @required
    id: String
    pushName: String
    @required
    lid: String
    @required
    jid: String
}

structure Payload {
    @required
    id: String
    @required
    timestamp: Long
    @required
    from: String
    @required
    fromMe: Boolean
    body: String
    to: String
    participant: String
    replyTo: String
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
    @jsonName("Chat")
    chat: String
    @jsonName("Sender")
    sender: String
    @jsonName("IsFromMe")
    isFromMe: Boolean
    @jsonName("IsGroup")
    isGroup: Boolean
    @jsonName("AddressingMode")
    addressingMode: String
    @jsonName("SenderAlt")
    senderAlt: String
    @jsonName("RecipientAlt")
    recipientAlt: String
    @jsonName("PushName")
    pushName: String
}
