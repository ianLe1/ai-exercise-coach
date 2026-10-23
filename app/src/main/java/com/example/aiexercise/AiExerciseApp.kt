package com.example.aiexercise

import android.app.Application
import com.example.aiexercise.platform.AppContainer

class AiExerciseApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Android 模拟器的 10.0.2.2（宿主机回环）在部分镜像上只接受纯 IPv4 连接：
        // Java 默认的双栈套接字会创建 AF_INET6 socket 并把 IPv4 地址映射成 IPv6，
        // 导致连接一直停在 SYN_SENT 直到超时。强制 IPv4 栈可让本机 modlens 桥/内网服务可达。
        System.setProperty("java.net.preferIPv4Stack", "true")
        container = AppContainer(this)
    }
}
