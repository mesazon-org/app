$version: "2.0"

namespace io.rikkos.gateway.smithy

structure WahaStopTyping {
    @required
    id: String
    @required
    timestamp: Long
    @required
    event: String
    @required
    session: String
    @required
    me: StopTypingMe
    @required
    payload: StopTypingPayload
}

structure StopTypingMe {
    @required
    id: String
    pushName: String
    @required
    lid: String
    @required
    jid: String
}

structure StopTypingPayload {
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
    data: StopTypingInternalData
}

structure StopTypingInternalData {
    @required
    @jsonName("Info")
    info: StopTypingInternalInfo
}

structure StopTypingInternalInfo {
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
