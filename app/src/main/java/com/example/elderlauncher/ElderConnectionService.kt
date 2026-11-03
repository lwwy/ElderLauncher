package com.example.elderlauncher

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/**
 * 通话连接服务：系统把来电/去电都交给本服务创建 Connection，
 * 由 InCallService 负责界面呈现。这是成为“默认电话应用”的标准做法。
 */
class ElderConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest
    ): Connection {
        val conn = object : Connection() {
            override fun onAnswer() = setActive()
            override fun onDisconnect() {
                setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
                destroy()
            }

            override fun onReject() {
                setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
                destroy()
            }
        }
        conn.setConnectionCapabilities(
            Connection.CAPABILITY_ANSWER or
                    Connection.CAPABILITY_DISCONNECT or
                    Connection.CAPABILITY_REJECT
        )
        conn.setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
        conn.setRinging()
        return conn
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest
    ): Connection {
        val conn = object : Connection() {
            override fun onDisconnect() {
                setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
                destroy()
            }
        }
        conn.setConnectionCapabilities(Connection.CAPABILITY_DISCONNECT)
        conn.setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
        conn.setDialing()
        return conn
    }

    override fun onConference(c1: Connection?, c2: Connection?) {}
}
