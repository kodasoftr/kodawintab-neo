package com.example.koda.engine

object KodaEngineProtocol {
    const val SERVER_HOST = "127.0.0.1"
    const val SERVER_PORT = 47291

    const val CMD_HANDSHAKE: Byte = 0x01
    const val CMD_INJECT_STYLUS_BATCH: Byte = 0x02
    const val CMD_INJECT_MOUSE: Byte = 0x03
    const val CMD_INJECT_KEY: Byte = 0x04
    const val CMD_QUERY_FOREGROUND_APPS: Byte = 0x05
    const val CMD_PING: Byte = 0x06
    const val CMD_VBUS_STATUS: Byte = 0x07
    const val CMD_GRANT_OVERLAY_OP: Byte = 0x08
    const val CMD_SET_POINTER_ICON: Byte = 0x09
    const val CMD_HIDE_NATIVE_POINTER: Byte = 0x0A
    const val CMD_POLL_GLOBAL_POINTER: Byte = 0x0B
    const val CMD_REGISTER_ROUTED_APPS: Byte = 0x0C
    const val CMD_MOVE_TASK_TO_DISPLAY: Byte = 0x0D
    const val CMD_SET_PEN_DISPLAY_ID: Byte = 0x0E
    const val CMD_SHUTDOWN: Byte = 0x7F

    const val STATUS_OK: Byte = 0x00
    const val STATUS_ERR_AUTH: Byte = 0x01
    const val STATUS_ERR_INJECT: Byte = 0x02

    const val INJECT_MODE_ASYNC = 0
}
