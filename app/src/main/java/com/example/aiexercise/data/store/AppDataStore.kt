package com.example.aiexercise.data.store

import com.example.aiexercise.data.model.AppData
import kotlinx.serialization.json.Json
import java.io.File

/** 持久化数据文件格式（可替换点：换 SQLite/Room/云端同步时只替换 AppDataStore 实现）。 */
object AppJson {
    val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(data: AppData): String = json.encodeToString(AppData.serializer(), data)

    fun decode(raw: String): AppData = json.decodeFromString(AppData.serializer(), raw)
}

interface AppDataStore {
    suspend fun load(): AppData?
    suspend fun save(data: AppData)
}

/**
 * 单文件 JSON 存储。
 * 写入用“临时文件 + 重命名”保证原子性，避免进程被杀导致数据文件损坏。
 */
class JsonFileStore(private val file: File) : AppDataStore {

    override suspend fun load(): AppData? {
        if (!file.exists()) return null
        return AppJson.decode(file.readText(Charsets.UTF_8))
    }

    override suspend fun save(data: AppData) {
        val parent = file.parentFile
        parent?.mkdirs()
        val temp = File(parent, "${file.name}.tmp")
        temp.writeText(AppJson.encode(data), Charsets.UTF_8)
        if (file.exists() && !file.delete()) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
            return
        }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}

/** 纯内存实现：JVM 单测与预览用。 */
class InMemoryStore(initial: AppData? = null) : AppDataStore {
    var saved: AppData? = initial
        private set

    override suspend fun load(): AppData? = saved

    override suspend fun save(data: AppData) {
        saved = data
    }
}
